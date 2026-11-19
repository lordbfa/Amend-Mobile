<?php
/**
 * Plugin Name: amend launch meta
 * Description: amend is live on Google Play. Rewrites any leftover "closed testing / public launch coming soon" wording in the page head (meta descriptions, Open Graph and X tags, JSON-LD) so search engines and link previews show the current status. Only the <head> is changed, so dated blog posts keep their history. Deactivate to revert.
 * Version: 1.0.0
 * Author: amend
 * License: GPL-3.0-or-later
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

const AMEND_PLAY_URL = 'https://play.google.com/store/apps/details?id=app.amend.messenger';

/** Old wording => current wording. Longest phrases first. */
function amend_launch_meta_map() {
	return array(
		'now in closed testing on Google Play with a public launch coming soon'  => 'now live on Google Play',
		'in closed testing on Google Play with a public launch coming soon'      => 'live on Google Play',
		'in closed testing on Google Play, with a public launch coming soon'     => 'live on Google Play',
		'in closed testing on Google Play, with the public launch coming soon'   => 'live on Google Play',
		'in closed testing on Google Play right now, and the public launch is coming soon' => 'live on Google Play',
		'Closed testing is full right now. See the status and the public launch.' => 'amend is live on Google Play.',
		'now in closed testing on Google Play' => 'now live on Google Play',
		'in closed testing on Google Play'     => 'live on Google Play',
		'in its final review on Google Play'   => 'live on Google Play',
		' with a public launch coming soon'    => '',
		' with the public launch coming soon'  => '',
	);
}

function amend_launch_meta_filter( $html ) {
	$end = stripos( $html, '</head>' );
	if ( false === $end ) {
		return $html;
	}
	$head = substr( $html, 0, $end );
	$map  = amend_launch_meta_map();
	$head = str_ireplace( array_keys( $map ), array_values( $map ), $head );
	// JSON-LD may escape slashes; the phrases above contain none, so they match either way.
	return $head . substr( $html, $end );
}

add_action(
	'template_redirect',
	function () {
		if ( is_admin() || wp_doing_ajax() || ( defined( 'REST_REQUEST' ) && REST_REQUEST ) || is_feed() ) {
			return;
		}
		ob_start( 'amend_launch_meta_filter' );
	},
	0
);
