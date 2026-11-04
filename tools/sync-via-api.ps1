# Publishes the current checkout's files to GitHub through the REST API.
#
# For when github.com:443 is unreachable (git push needs a proxy that is off) but api.github.com works: the commit is
# assembled server-side from blobs, a tree based on the remote head, and one commit object, then main is fast-forwarded.
#
# Only the files that differ from the remote head are uploaded; files that disappeared locally are deleted.
param(
    [string]$Repo = 'wind-wh/AOmarchy',
    [string]$Branch = 'main'
)

$ErrorActionPreference = 'Stop'
$cred = "protocol=https`nhost=github.com`n" | git credential fill 2>$null
$token = ($cred | Select-String '^password=').Line -replace '^password=', ''
if (-not $token) { throw 'no GitHub token in the credential helper' }
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'dsh-sync' }
$api = "https://api.github.com/repos/$Repo"

$remoteRef = Invoke-RestMethod -Uri "$api/git/ref/heads/$Branch" -Headers $headers -TimeoutSec 30
$remoteSha = $remoteRef.object.sha
$remoteCommit = Invoke-RestMethod -Uri "$api/git/commits/$remoteSha" -Headers $headers -TimeoutSec 30
$remoteTree = $remoteCommit.tree.sha
$flat = Invoke-RestMethod -Uri "$api/git/trees/$remoteTree`?recursive=1" -Headers $headers -TimeoutSec 60

$remote = @{}
foreach ($e in $flat.tree) { if ($e.type -eq 'blob') { $remote[$e.path] = $e.sha } }

$local = @{}
$modes = @{}
foreach ($line in (git ls-files -s)) {
    $parts = $line -split "`t"
    $meta = $parts[0] -split ' '
    $path = $parts[1]
    $modes[$path] = $meta[0]
    $local[$path] = $meta[1]
}

$entries = New-Object System.Collections.Generic.List[object]
$uploaded = 0
foreach ($path in $local.Keys) {
    if ($remote.ContainsKey($path) -and $remote[$path] -eq $local[$path]) { continue }
    $bytes = [IO.File]::ReadAllBytes((Join-Path (Get-Location) $path))
    $b64 = [Convert]::ToBase64String($bytes)
    $blob = Invoke-RestMethod -Uri "$api/git/blobs" -Method Post -Headers $headers `
        -Body (@{ content = $b64; encoding = 'base64' } | ConvertTo-Json -Compress) `
        -ContentType 'application/json' -TimeoutSec 120
    $entries.Add(@{ path = $path; mode = $modes[$path]; type = 'blob'; sha = $blob.sha })
    $uploaded++
}
foreach ($path in $remote.Keys) {
    if ($local.ContainsKey($path)) { continue }
    $entries.Add(@{ path = $path; mode = '100644'; type = 'blob'; sha = $null })
}
if ($entries.Count -eq 0) {
    Write-Output "already in sync with $remoteSha"
    exit 0
}

$treeJson = '{"base_tree":"' + $remoteTree + '","tree":' + (($entries | ConvertTo-Json -Depth 6 -Compress) -join '') + '}'
$tree = Invoke-RestMethod -Uri "$api/git/trees" -Method Post -Headers $headers -Body $treeJson -ContentType 'application/json' -TimeoutSec 120

$message = ((git log -1 --pretty=%B) -join "`n").Trim()
$commit = Invoke-RestMethod -Uri "$api/git/commits" -Method Post -Headers $headers `
    -Body (@{ message = $message; tree = $tree.sha; parents = @($remoteSha) } | ConvertTo-Json -Compress) `
    -ContentType 'application/json' -TimeoutSec 60

$ref = Invoke-RestMethod -Uri "$api/git/refs/heads/$Branch" -Method Patch -Headers $headers `
    -Body (@{ sha = $commit.sha; force = $false } | ConvertTo-Json -Compress) `
    -ContentType 'application/json' -TimeoutSec 60

Write-Output "pushed $uploaded file(s): $($remoteSha.Substring(0,7)) -> $($commit.sha.Substring(0,7)) on $Branch"
