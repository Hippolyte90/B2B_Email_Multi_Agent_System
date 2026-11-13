param([switch]$PullModel)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot 'data/runtime'
New-Item -ItemType Directory -Path (Join-Path $runtimeDir 'ollama-models') -Force | Out-Null
$ollamaExecutable = (Get-Command ollama -ErrorAction Stop).Source
$oldHost = $env:OLLAMA_HOST
$oldModels = $env:OLLAMA_MODELS
$oldCloud = $env:OLLAMA_NO_CLOUD
try {
    $env:OLLAMA_HOST = '127.0.0.1:11435'
    $env:OLLAMA_MODELS = Join-Path $runtimeDir 'ollama-models'
    $env:OLLAMA_NO_CLOUD = '1'
    $running = $false
    try { $null = Invoke-RestMethod 'http://127.0.0.1:11435/api/version' -TimeoutSec 2; $running = $true } catch { }
    if (!$running) {
        $process = Start-Process -FilePath $ollamaExecutable -ArgumentList 'serve' -WorkingDirectory $projectRoot -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $runtimeDir 'ollama-stdout.log') -RedirectStandardError (Join-Path $runtimeDir 'ollama-stderr.log') -PassThru
        $process.Id | Set-Content (Join-Path $runtimeDir 'ollama-server.pid')
        Write-Output "Ollama démarré : PID $($process.Id), port loopback 11435, cloud désactivé."
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            try { $null = Invoke-RestMethod 'http://127.0.0.1:11435/api/version' -TimeoutSec 1; $ready = $true; break } catch { Start-Sleep -Milliseconds 500 }
        }
        if (!$ready) { throw 'Le serveur local ne répond pas ; consulter data/runtime/ollama-stderr.log.' }
    } else {
        Write-Output "Un serveur répond déjà sur 11435. Vérifiez qu'il a été démarré avec OLLAMA_NO_CLOUD=1 ; aucune configuration existante modifiée."
    }
    if ($PullModel) {
        & $ollamaExecutable pull qwen2.5:0.5b
        if ($LASTEXITCODE -ne 0) { throw 'Le téléchargement du modèle a échoué.' }
    }
    Write-Output 'Configuration du projet : config/application/inference.properties'
} finally {
    $env:OLLAMA_HOST = $oldHost
    $env:OLLAMA_MODELS = $oldModels
    $env:OLLAMA_NO_CLOUD = $oldCloud
}
