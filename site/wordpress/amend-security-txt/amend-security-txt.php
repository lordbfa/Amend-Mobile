<?php
/*
Plugin Name: amend security.txt
Description: Serves /.well-known/security.txt (RFC 9116) so security researchers know how to report a problem in amend privately.
Version: 1.0.0
Author: amend
*/

if (!defined('ABSPATH')) {
    exit;
}

/**
 * The file is generated on each request, so its "Expires" date never runs out
 * (RFC 9116 asks for less than a year ahead; we use 180 days).
 */
function amend_security_txt_body() {
    $expires = gmdate('Y-m-d\TH:i:s\Z', time() + 180 * DAY_IN_SECONDS);
    $lines = array(
        '# amend - private messenger on Nostr. Please report security problems privately.',
        'Contact: https://github.com/lordbfa/Amend-Mobile/security/advisories/new',
        'Contact: mailto:lordbfa@getamend.app',
        'Expires: ' . $expires,
        'Policy: https://github.com/lordbfa/Amend-Mobile/blob/main/SECURITY.md',
        'Acknowledgments: https://getamend.app/blog/#release-notes',
        'Preferred-Languages: en, fi, bn',
        'Canonical: https://getamend.app/.well-known/security.txt',
    );
    return implode("\n", $lines) . "\n";
}

add_action('parse_request', function () {
    $path = isset($_SERVER['REQUEST_URI']) ? wp_parse_url(wp_unslash($_SERVER['REQUEST_URI']), PHP_URL_PATH) : '';
    if ($path !== '/.well-known/security.txt' && $path !== '/security.txt') {
        return;
    }
    status_header(200);
    nocache_headers();
    header('Content-Type: text/plain; charset=utf-8');
    header('X-Content-Type-Options: nosniff');
    echo amend_security_txt_body(); // phpcs:ignore WordPress.Security.EscapeOutput -- plain text built from constants
    exit;
}, 0);
