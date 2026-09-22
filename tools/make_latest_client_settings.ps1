param(
    [string]$SourceDirectory = 'C:\backup\AAPS\aapsLogs\Client_SMF731B',
    [string]$OutputPath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $OutputPath) {
    $OutputPath = Join-Path $SourceDirectory 'AutoISF_settings_Client_SMF731B_Latest.docx'
}

# Export timestamps survive copying, unlike Windows modification times.
$candidates = Get-ChildItem -LiteralPath $SourceDirectory -File -Filter 'AutoISF_settings_Client_SMF731B_*.txt' | ForEach-Object {
    if ($_.Name -match '^AutoISF_settings_Client_SMF731B_(\d{8}_\d{6})\.txt$') {
        $exportTime = [datetime]::MinValue
        if ([datetime]::TryParseExact($Matches[1], 'yyyyMMdd_HHmmss', [cultureinfo]::InvariantCulture,
                [System.Globalization.DateTimeStyles]::None, [ref]$exportTime)) {
            [pscustomobject]@{ File = $_; ExportTime = $exportTime }
        }
    }
}
$latest = $candidates | Sort-Object ExportTime -Descending | Select-Object -First 1
if (-not $latest) { throw "No dated Client settings exports found in $SourceDirectory" }
$lines = [System.IO.File]::ReadAllLines($latest.File.FullName)
if (-not $lines.Count) { throw "Settings export is empty: $($latest.File.FullName)" }

function ParagraphXml([string]$Text, [string]$Style = 'Normal') {
    $escaped = [System.Security.SecurityElement]::Escape($Text)
    return '<w:p><w:pPr><w:pStyle w:val="' + $Style + '"/></w:pPr><w:r><w:t xml:space="preserve">' + $escaped + '</w:t></w:r></w:p>'
}
$body = [System.Text.StringBuilder]::new()
[void]$body.Append((ParagraphXml 'Latest AutoISF Client Settings' 'Title'))
[void]$body.Append((ParagraphXml ('Source file: ' + $latest.File.Name)))
[void]$body.Append((ParagraphXml 'The snapshot time and source below describe the exported settings. They can differ from the file creation time.'))
[void]$body.Append((ParagraphXml ''))
foreach ($line in $lines) { [void]$body.Append((ParagraphXml $line)) }

$parts = @{
    '[Content_Types].xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>'
    '_rels/.rels' = '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'
    'word/_rels/document.xml.rels' = '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>'
    'word/styles.xml' = '<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:spacing w:after="40"/><w:widowControl/></w:pPr><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:color w:val="000000"/><w:sz w:val="20"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:after="180"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>'
    'word/document.xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' + $body.ToString() + '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="850" w:right="850" w:bottom="850" w:left="850" w:header="400" w:footer="400" w:gutter="0"/></w:sectPr></w:body></w:document>'
}
Add-Type -AssemblyName System.IO.Compression
$temporaryPath = Join-Path ([System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($OutputPath))) ([guid]::NewGuid().ToString() + '.tmp')
try {
    $stream = [System.IO.File]::Open($temporaryPath, [System.IO.FileMode]::CreateNew)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($name in $parts.Keys) {
                [void][xml]$parts[$name]
                $entry = $archive.CreateEntry($name)
                $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
                try { $writer.Write($parts[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    # Failed conversion leaves the previous Latest intact.
    if ([System.IO.File]::Exists($OutputPath)) {
        [System.IO.File]::Replace($temporaryPath, $OutputPath, [System.Management.Automation.Language.NullString]::Value)
    } else {
        [System.IO.File]::Move($temporaryPath, $OutputPath)
    }
} finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath }
}
Write-Output "Settings source: $($latest.File.FullName)"
Write-Output "Latest Word file: $OutputPath"
