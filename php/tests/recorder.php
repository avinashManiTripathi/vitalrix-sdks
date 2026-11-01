<?php
// Router for `php -S`: records method, path, content type and key of each request, answers like VitalRix.
$file = getenv('RECORDER_FILE');
file_put_contents($file, json_encode([$_SERVER['REQUEST_URI'], $_SERVER['CONTENT_TYPE'] ?? '', $_SERVER['HTTP_X_API_KEY'] ?? '']) . "\n", FILE_APPEND | LOCK_EX);
if (str_contains($_SERVER['CONTENT_TYPE'] ?? '', 'protobuf')) {
    header('content-type: application/x-protobuf');
    return true;
}
header('content-type: application/json');
echo '{"success":true,"data":{}}';
