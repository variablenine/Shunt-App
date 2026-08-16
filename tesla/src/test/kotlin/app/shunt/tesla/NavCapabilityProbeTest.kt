package app.shunt.tesla

import app.shunt.core.GeoPoint
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * The probe exists to answer a question with evidence, so the one thing it must
 * never do is agree with a command that didn't actually move the car. These
 * tests are about exactly that: a 200 with no change in the car's state is a
 * failure, not a success.
 */
class NavCapabilityProbeTest {

    private val server = MockWebServer()

    private val pointA = GeoPoint(39.0, -98.0)
    private val pointB = GeoPoint(39.05, -97.95)

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun probe() = NavCapabilityProbe(
        http = OkHttpClient(),
        bearerToken = "test-token",
        vin = "5YJ3TESTVIN",
        account = TessieAccountClient(OkHttpClient(), server.url("").toString().trimEnd('/')),
        baseUrl = server.url("").toString().trimEnd('/'),
        settle = {}, // no waiting in tests
    )

    /** A vehicle-state body reporting the car aimed at [at]. */
    private fun stateAimedAt(at: GeoPoint?, name: String = "Test point"): String {
        val route = if (at == null) {
            """"drive_state":{}"""
        } else {
            """"drive_state":{"active_route_destination":"$name",""" +
                """"active_route_latitude":${at.lat},"active_route_longitude":${at.lon}}"""
        }
        return "{$route,\"charge_state\":{\"battery_level\":70}}"
    }

    private fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    @Test
    fun `a command the car obeys is reported as working`() = runTest {
        // Everything accepted, and the car's state follows whatever was last
        // sent — the shape of a channel that genuinely works.
        var lastSent: GeoPoint? = null
        // B's latitude (39.05) contains A's (39.0) as a prefix, so B is tested
        // for first — otherwise every request would look like it named A.
        fun pointIn(text: String): GeoPoint = if ("39.05" in text) pointB else pointA
        dispatch { request ->
            val path = request.path.orEmpty()
            when {
                path.contains("/state") -> MockResponse().setBody(stateAimedAt(lastSent))
                path.contains("share") -> {
                    lastSent = pointIn(java.net.URLDecoder.decode(path, "UTF-8"))
                    MockResponse().setBody("""{"result":true}""")
                }
                else -> {
                    // A chain ends where it ends: its last point is the one the
                    // car should be aiming at afterwards.
                    lastSent = pointIn(request.body.readUtf8().substringAfterLast('|'))
                    MockResponse().setBody("""{"response":{"result":true,"reason":""}}""")
                }
            }
        }

        val steps = probe().run(pointA, pointB)

        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it.accepted }, "every channel was accepted by the server")
        assertTrue(
            steps.all { it.landed },
            "each channel moved the car to what it sent: " +
                steps.filterNot { it.landed }.joinToString { it.channel },
        )
    }

    @Test
    fun `a command that returns success but leaves the car where it was does not count`() = runTest {
        // This is the failure the probe is built to catch. Tessie answering 200
        // says a request was accepted somewhere upstream; only the car's own
        // state says the car did anything. Here it never moves.
        dispatch { request ->
            if (request.path.orEmpty().contains("/state")) {
                MockResponse().setBody(stateAimedAt(GeoPoint(41.0, -95.0), "Somewhere else"))
            } else {
                MockResponse().setBody("""{"response":{"result":true,"reason":""},"result":true}""")
            }
        }

        val steps = probe().run(pointA, pointB)

        assertTrue(steps.all { it.accepted }, "the server said yes to everything")
        assertTrue(steps.none { it.landed }, "but nothing reached the car, so nothing works")
        assertTrue(
            steps.all { "accepted, but the car did not move" in it.verdict },
            "the verdict must not read as success",
        )
    }

    @Test
    fun `a rejected command records why, and is not called a success`() = runTest {
        dispatch { request ->
            if (request.path.orEmpty().contains("/state")) {
                MockResponse().setBody(stateAimedAt(null))
            } else {
                MockResponse().setResponseCode(400)
                    .setBody("""{"response":{"result":false,"reason":"invalid_command"}}""")
            }
        }

        val steps = probe().run(pointA, pointB)

        assertTrue(steps.none { it.accepted })
        assertTrue(steps.none { it.landed })
        assertTrue(
            steps.all { "invalid_command" in it.response },
            "the car's own wording is the useful part and must be kept",
        )
    }

    @Test
    fun `every channel worth trying is actually tried`() = runTest {
        dispatch { MockResponse().setResponseCode(500).setBody("nope") }

        val channels = probe().run(pointA, pointB).map { it.channel }

        assertEquals(
            listOf(
                "navigation_waypoints_request",
                "navigation_gps_request order=1",
                "navigation_gps_request order=3",
                "share coordinates",
                "share geo: URI",
                "share OpenStreetMap link",
                "share Apple Maps link",
                // Google is a *string handed to the car*, not a call to Google.
                // Added because a real car rejected everything except plain
                // coordinates and the Apple link, and the maintainer has had
                // Google links work by hand. See ShareFormat.GOOGLE_MAPS.
                "share Google Maps link",
                "share Google Maps place link",
            ),
            channels,
        )
    }

    @Test
    fun `nothing in a probed share string calls Google`() {
        // The keyless rule (CLAUDE.md SS3) is about services Shunt depends on,
        // and a URL sent to a car is not one — but the difference is easy to
        // lose later, so it is pinned here. Every format must be a literal
        // coordinate string with no key, no redirect and nothing for Shunt
        // itself to fetch.
        val p = GeoPoint(39.0, -98.0)
        for (format in NavCapabilityProbe.ShareFormat.entries) {
            val rendered = format.render(p)
            assertTrue(
                "39.0" in rendered && ("-98.0" in rendered || "-98%2C" in rendered || "%2C-98" in rendered),
                "${format.label} must carry the coordinates literally: $rendered",
            )
            assertTrue(
                "key=" !in rendered && "apikey" !in rendered.lowercase(),
                "${format.label} must not carry a credential: $rendered",
            )
        }
    }

    @Test
    fun `the read-back asks the car, not Tessie's cache`() = runTest {
        // The bug this exists to prevent, seen on a real car: every accepted
        // step reported "car state unreadable" because the state was read from
        // a cache filled BEFORE the command was sent. A probe that reads a
        // cache is measuring the cache, and its answers are worthless.
        val stateQueries = mutableListOf<String>()
        dispatch { request ->
            val path = request.path.orEmpty()
            if (path.contains("/state")) {
                stateQueries += path
                MockResponse().setBody(stateAimedAt(pointA))
            } else {
                MockResponse().setBody("""{"result":true,"response":{"result":true,"reason":""}}""")
            }
        }

        probe().run(pointA, pointB)

        assertTrue(stateQueries.isNotEmpty(), "the probe must read the car back at all")
        assertTrue(
            stateQueries.all { "use_cache=false" in it },
            "every read-back must bypass the cache: $stateQueries",
        )
    }

    @Test
    fun `an unreadable car says why, rather than just being unreadable`() = runTest {
        // "Accepted, car state unreadable" was the entire result on a real car,
        // and it is not an answer — it cannot be told apart from the car
        // ignoring the command. Whatever the reason is, it has to reach the
        // person reading the report.
        dispatch { request ->
            if (request.path.orEmpty().contains("/state")) {
                MockResponse().setResponseCode(408)
            } else {
                MockResponse().setBody("""{"result":true,"response":{"result":true,"reason":""}}""")
            }
        }

        val accepted = probe().run(pointA, pointB).filter { it.accepted }

        assertTrue(accepted.isNotEmpty(), "the fixture must accept something")
        for (step in accepted) {
            assertTrue(
                step.readProblem != null,
                "${step.channel} was accepted but its read failed silently",
            )
            assertTrue(
                "car state unreadable" !in step.verdict,
                "${step.channel} must say why, not just that: ${step.verdict}",
            )
        }
    }

    @Test
    fun `a car that answers with no route is not confused with a failed read`() = runTest {
        // Two very different findings that used to look identical: the car
        // could not be asked, versus the car was asked and is navigating
        // nowhere. The second is a real result — the server accepted a command
        // the car then ignored, which is the most damning thing this can find.
        dispatch { request ->
            if (request.path.orEmpty().contains("/state")) {
                MockResponse().setBody(stateAimedAt(null))
            } else {
                MockResponse().setBody("""{"result":true,"response":{"result":true,"reason":""}}""")
            }
        }

        val accepted = probe().run(pointA, pointB).filter { it.accepted }

        assertTrue(accepted.isNotEmpty(), "the fixture must accept something")
        assertTrue(
            accepted.all { it.readProblem == "the car reports no active route" },
            "an answering car with no route must be reported as such: " +
                accepted.map { it.readProblem },
        )
    }

    @Test
    fun `the same place reached by a slightly different road still counts`() = runTest {
        // The car reports where its destination snapped to on the road network,
        // not the coordinate it was handed, so an exact match would report
        // failure for a command that worked perfectly.
        val sent = GeoPoint(39.0, -98.0)
        val snapped = GeoPoint(39.001, -98.001) // ~140 m away
        assertTrue(NavCapabilityProbe.isSamePlace(sent, snapped))
        assertTrue(!NavCapabilityProbe.isSamePlace(sent, GeoPoint(39.05, -98.0)))
    }
}
