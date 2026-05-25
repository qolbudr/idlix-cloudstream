/*
 * Standalone JVM smoke test for the Idlix play-info -> claim -> redeem flow.
 *
 * Why this exists:
 *   - The Cloudstream stub jar is Android-only, so we cannot just call
 *     IdlixProvider.fetchPlayData() from the JVM.
 *   - Instead we mirror the exact wire contract of `IdlixProvider#loadLinks`
 *     here using OkHttp + Jackson directly. If this test produces a
 *     stream URL + subtitles, the production Kotlin code will too.
 *   - It also doubles as a regression check for the JSON shapes parsed
 *     into IdlixModels.kt — those data classes are reused as-is.
 *
 * z2.idlixku.com is fronted by Cloudflare, so direct curl returns the
 * "Just a moment..." HTML challenge. On a real device Cloudstream's
 * CloudflareKiller solves it once via WebView. From a vanilla JVM we
 * route through the existing `proxy-cloudflare-mocha` service, the same
 * one the upstream TS addon uses.
 *
 * Run via:  IdlixProvider/src/test/runSmokeTest.sh
 */
@file:JvmName("SmokeTest")

package com.idlix

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val IDLIX = "https://z2.idlixku.com"
private const val PROXY_URL = "https://proxy-cloudflare-mocha.vercel.app/api/*"

private val mapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .build()

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .callTimeout(150, TimeUnit.SECONDS)
    .build()

private val JSON = "application/json".toMediaType()

/** Wrapper for a single proxy round-trip. */
private data class ProxyResp(
    val proxyStatus: Int,
    val upstreamStatus: Int?,
    /** raw JSON body returned by the upstream */
    val upstreamBody: String?,
    val proxyUsed: String?,
    /** Set-Cookie list from the upstream (browser semantics: last name wins). */
    val setCookies: List<String>,
)

private fun proxyRequest(
    targetUrl: String,
    method: String = "GET",
    contentType: String? = null,
    data: Any? = null,
    extraHeaders: Map<String, String> = emptyMap(),
    pinProxy: String? = null,
): ProxyResp {
    val body = mutableMapOf<String, Any?>(
        "targetUrl" to targetUrl,
        "method" to method,
    )
    if (contentType != null) body["contentType"] = contentType
    if (data != null) body["data"] = data
    if (extraHeaders.isNotEmpty()) body["headers"] = extraHeaders
    if (pinProxy != null) body["targetProxy"] = pinProxy

    val payload = mapper.writeValueAsBytes(body)
    val req = Request.Builder()
        .url(PROXY_URL)
        .post(payload.toRequestBody(JSON))
        .build()

    http.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        val tree = runCatching { mapper.readTree(text) }.getOrNull()
        val content: JsonNode? = tree?.get("content")
        val debug: JsonNode? = tree?.get("debug")

        val upstreamBody: String? = when {
            content == null || content.isNull -> null
            content.isTextual -> content.asText()
            else -> content.toString()
        }

        val setCookies: List<String> = debug?.get("setCookies")
            ?.takeIf { it.isArray }
            ?.mapNotNull { if (it.isTextual) it.asText() else null }
            ?: emptyList()

        return ProxyResp(
            proxyStatus = resp.code,
            upstreamStatus = debug?.get("upstreamStatus")?.takeIf { it.isInt }?.asInt(),
            upstreamBody = upstreamBody,
            proxyUsed = debug?.get("proxyUsed")?.asText(),
            setCookies = setCookies,
        )
    }
}

private inline fun <reified T> ProxyResp.parseJsonAs(): T {
    val raw = upstreamBody
        ?: error("no upstream body (proxyStatus=$proxyStatus, upstreamStatus=$upstreamStatus)")
    return mapper.readValue(raw)
}

private fun assertOk(label: String, resp: ProxyResp) {
    val ok = resp.proxyStatus == 200 && resp.upstreamStatus in 200..299
    if (!ok) {
        error(
            "$label failed: proxyStatus=${resp.proxyStatus}, upstreamStatus=${resp.upstreamStatus}, " +
                "body=${resp.upstreamBody?.take(400)}",
        )
    }
}

/**
 * Browser semantics for `Set-Cookie`: when several cookies share a name,
 * the last one wins. Path/domain/expiry are ignored; idlix uses a single
 * host with `/`.
 */
private fun List<String>.collapseToCookieHeader(): String {
    val map = LinkedHashMap<String, String>()
    forEach { line ->
        val head = line.substringBefore(';').trim()
        val eq = head.indexOf('=')
        if (eq <= 0) return@forEach
        val name = head.substring(0, eq).trim()
        val value = head.substring(eq + 1).trim()
        if (name.isNotEmpty()) map[name] = "$name=$value"
    }
    return map.values.joinToString("; ")
}

private val baseHeaders = mapOf(
    "Origin" to IDLIX,
    "Referer" to "$IDLIX/",
    "Accept" to "application/json, text/plain, */*",
)

fun main() {
    println("================ IdlixProvider smoke test ================")

    try {
        runSmokeTest()
    } catch (e: Throwable) {
        System.err.println("\n================ FAIL ================")
        System.err.println(e.message)
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun runSmokeTest() {
    // 1) Pick a real movie from the latest catalog.
    println("\n[1/4] GET /api/movies?sort=createdAt")
    val moviesResp = proxyRequest(
        "$IDLIX/api/movies?page=1&limit=5&sort=createdAt",
        extraHeaders = baseHeaders,
    )
    assertOk("/api/movies", moviesResp)
    val movies: ApiResponse = moviesResp.parseJsonAs()
    val movie = movies.data.firstOrNull() ?: error("no movies returned")
    println("    -> picked '${movie.title}' (${movie.id}) slug=${movie.slug}")
    println("    -> proxy used: ${moviesResp.proxyUsed}")

    // 2) play-info: capture cookies + gateToken
    println("\n[2/4] GET /api/watch/play-info/movie/${movie.id}")
    val playResp = proxyRequest(
        "$IDLIX/api/watch/play-info/movie/${movie.id}",
        extraHeaders = baseHeaders,
        pinProxy = moviesResp.proxyUsed, // pin proxy for cookie+IP affinity
    )
    assertOk("play-info", playResp)
    val playInfo: PlayInfoRes = playResp.parseJsonAs()
    require(playInfo.gateToken.isNotBlank()) { "gateToken empty" }
    val cookieHeader = playResp.setCookies.collapseToCookieHeader()
    require(cookieHeader.isNotBlank()) { "no cookies set by play-info" }
    val unlockAt = playInfo.unlockAt
    val serverNow = playInfo.serverNow
    println("    -> gateToken: ${playInfo.gateToken.take(40)}...")
    println("    -> serverNow=$serverNow  unlockAt=$unlockAt  gap=${unlockAt - serverNow}ms")
    println("    -> cookies:   $cookieHeader")

    // 3) Wait for the gate then claim. Reuse the same proxy + cookie.
    val waitMs = (unlockAt - serverNow).coerceAtLeast(0L) + 250
    if (waitMs > 0) {
        println("    -> sleeping ${waitMs}ms for gate unlock...")
        Thread.sleep(waitMs)
    }

    println("\n[3/4] POST /api/watch/session/claim  (gateToken)")
    val claimResp = proxyRequest(
        "$IDLIX/api/watch/session/claim",
        method = "POST",
        contentType = "application/json",
        data = mapOf("gateToken" to playInfo.gateToken),
        extraHeaders = baseHeaders + ("Cookie" to cookieHeader),
        pinProxy = moviesResp.proxyUsed,
    )
    assertOk("claim", claimResp)
    val claim: ClaimRes = claimResp.parseJsonAs()
    require(claim.claim.isNotBlank()) { "claim token empty" }
    val redeemUrl = claim.redeemUrl ?: "https://e2e.majorplay.net/api/play"
    println("    -> claim:     ${claim.claim.take(40)}...")
    println("    -> redeemUrl: $redeemUrl")
    println("    -> title:     ${claim.title}")

    // 4) Redeem -> stream URL + subs.
    //    NOTE: must be JSON {claim, mode}. The original TS addon sent
    //    text/plain; the server now rejects that with `{code:"invalid-body"}`.
    //    Verified live on 2026-05-23.
    println("\n[4/4] POST $redeemUrl  (JSON {claim, mode})")
    val redeemResp = proxyRequest(
        redeemUrl,
        method = "POST",
        contentType = "application/json",
        data = mapOf("claim" to claim.claim, "mode" to "browser"),
        extraHeaders = baseHeaders,
    )
    assertOk("redeem", redeemResp)
    val redeem: RedeemRes = redeemResp.parseJsonAs()
    val streamUrl = redeem.url ?: error("no stream url in redeem response")
    println("    -> streamUrl: $streamUrl")
    println("    -> subtitles (${redeem.subtitles.size}):")
    redeem.subtitles.forEach { s ->
        println("       - [${s.lang}] ${s.label}: ${s.path}")
    }

    println("\n================ PASS ================")
    println("stream url returned : ${streamUrl.take(120)}...")
    println("subtitles returned  : ${redeem.subtitles.size}")
}
