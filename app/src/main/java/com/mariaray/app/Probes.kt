package com.mariaray.app

object Probes {
    // Multiple HTTP endpoints to try in order.
    // Each entry is (url, expectedCode).
    val HTTP: List<Pair<String, Int>> = listOf(
        "http://cp.cloudflare.com/generate_204" to 204,
        "http://www.gstatic.com/generate_204" to 204,
        "http://connectivitycheck.gstatic.com/generate_204" to 204,
        "http://detectportal.firefox.com/success.txt" to 200,
        "http://1.1.1.1/cdn-cgi/trace" to 200,
        "http://www.msftconnecttest.com/connecttest.txt" to 200
    )

    // DNS servers, tried in order by Xray if the first fails.
    val DNS: List<String> = listOf(
        "1.1.1.1", "9.9.9.9", "8.8.8.8", "1.0.0.1", "8.8.4.4", "208.67.222.222"
    )
}
