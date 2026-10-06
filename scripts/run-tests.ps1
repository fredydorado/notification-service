<#
    Runs Maven on Windows against the Docker daemon inside WSL2.

    On a Windows machine where Docker runs as a native daemon inside a WSL2 distro
    (rather than as Docker Desktop), plain `mvn test` fails every integration test
    with "Could not find a valid Docker environment", for two reasons:

      1. WSL2 shuts the distro down when it goes idle, taking the daemon with it -
         including part way through a test run.
      2. Nothing tells the JVM where the daemon is; there is no Docker Desktop
         named pipe for Testcontainers to find.

    This script fixes both: it holds a WSL session open for exactly as long as the
    run lasts, makes sure the socat TCP bridge is listening, resolves the distro's
    current IP, and then invokes Maven with DOCKER_HOST pointing at it.

    On Linux with a native Docker daemon none of this is needed - just run `mvn test`.

    Usage (every argument is passed straight through to Maven; defaults to "test"):

        .\scripts\run-tests.ps1
        .\scripts\run-tests.ps1 test -Dtest=EventProcessingFlowIntTest
        .\scripts\run-tests.ps1 package

    Override the defaults with environment variables if needed:

        $env:WSL_DISTRO = 'Ubuntu-22.04'
        $env:DOCKER_BRIDGE_PORT = '2375'
#>

$ErrorActionPreference = 'Stop'

$mavenArgs = $args
if (-not $mavenArgs -or $mavenArgs.Count -eq 0) { $mavenArgs = @('test') }

$distro = if ($env:WSL_DISTRO) { $env:WSL_DISTRO } else { 'Ubuntu' }
$port = if ($env:DOCKER_BRIDGE_PORT) { $env:DOCKER_BRIDGE_PORT } else { '2375' }
$proxyName = 'docker-tcp-proxy'
$keepAlive = $null

function Invoke-Wsl {
    param([string] $Command)
    $output = wsl -d $distro -- bash -lc $Command
    return ($output | Out-String).Trim()
}

try {
    # 1. Hold the distro open. Without this WSL2 can stop it - and the daemon -
    #    part way through the run, which surfaces as an intermittent
    #    "Connection refused" or "DOCKER_HOST ... is not listening".
    Write-Host "[1/4] Holding $distro open for the duration of the run..." -ForegroundColor Cyan
    $keepAlive = Start-Process -FilePath 'wsl.exe' `
        -ArgumentList '-d', $distro, '--', 'sleep', 'infinity' `
        -WindowStyle Hidden -PassThru

    # 2. The daemon itself is a systemd service and starts with the distro; the
    #    TCP bridge is an ordinary container that may need starting or creating.
    Write-Host "[2/4] Checking the Docker daemon and the $proxyName bridge..." -ForegroundColor Cyan
    $server = Invoke-Wsl "docker version --format '{{.Server.Version}}'"
    if ($LASTEXITCODE -ne 0 -or -not $server) {
        throw "Docker is not responding inside ${distro}: $server"
    }
    Write-Host "      Docker Engine $server"

    $running = Invoke-Wsl "docker ps -q -f name=^$proxyName`$"
    if (-not $running) {
        $exists = Invoke-Wsl "docker ps -aq -f name=^$proxyName`$"
        if ($exists) {
            Write-Host "      Starting the existing $proxyName container..."
            Invoke-Wsl "docker start $proxyName" | Out-Null
        }
        else {
            Write-Host "      Creating the $proxyName container..."
            Invoke-Wsl ("docker run -d --name $proxyName --restart unless-stopped --network host " +
                        "-v /var/run/docker.sock:/var/run/docker.sock alpine/socat " +
                        "TCP-LISTEN:$port,fork,reuseaddr UNIX-CONNECT:/var/run/docker.sock") | Out-Null
        }
    }

    # 3. The distro's address is assigned by WSL and changes across restarts, so
    #    resolve it per run. Use the literal IPv4: the JVM may resolve "localhost"
    #    to ::1 and report the port closed.
    Write-Host "[3/4] Resolving the $distro address..." -ForegroundColor Cyan
    $ip = (Invoke-Wsl 'hostname -I').Split(' ')[0]
    if (-not $ip) { throw "Could not determine the IP address of $distro." }

    $bridge = "tcp://${ip}:${port}"
    $reachable = $false
    foreach ($attempt in 1..10) {
        try {
            Invoke-RestMethod -Uri "http://${ip}:${port}/_ping" -TimeoutSec 3 | Out-Null
            $reachable = $true
            break
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $reachable) {
        throw "The Docker bridge at $bridge did not respond. Check '$proxyName' inside $distro."
    }
    Write-Host "      DOCKER_HOST=$bridge"

    # 4. Run Maven with the daemon in reach.
    Write-Host "[4/4] mvn $($mavenArgs -join ' ')" -ForegroundColor Cyan
    Write-Host ''
    $env:DOCKER_HOST = $bridge
    # Maven writes warnings (Mockito, the JVM) to stderr. Under
    # ErrorActionPreference 'Stop', PowerShell 5.1 wraps a native command's stderr
    # in an ErrorRecord and would abort the run on the first such line, so the
    # preference is relaxed for the Maven call itself.
    $ErrorActionPreference = 'Continue'
    & mvn @mavenArgs
    exit $LASTEXITCODE
}
finally {
    Remove-Item Env:\DOCKER_HOST -ErrorAction SilentlyContinue
    if ($keepAlive -and -not $keepAlive.HasExited) {
        Stop-Process -Id $keepAlive.Id -Force -ErrorAction SilentlyContinue
    }
}
