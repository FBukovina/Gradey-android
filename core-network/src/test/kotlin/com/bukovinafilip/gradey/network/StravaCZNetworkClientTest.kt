package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test

class StravaCZNetworkClientTest {
    private val server = MockWebServer()
    private lateinit var client: StravaCZNetworkClient
    private val session = StravaCZStoredSession("session", "1234", "student", "100.00", serviceURL = "https://canteen.example")

    @Before fun setUp() {
        server.start()
        client = StravaCZNetworkClient(baseURL = server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun loginPreservesPasswordAndDecodesFlexibleBalance() = runTest {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "canteen=ready; Path=/").setBody("<html></html>"))
        server.enqueue(MockResponse().setBody("""{
          "sid":"session","s5url":"https://canteen.example","cislo":"1234","jmeno":"student",
          "uzivatel":{"jmeno":"Student Name","konto":"42,50","mena":"Kč","nazevJidelny":"School canteen"}
        }"""))
        val result = client.login(" 1234 ", " student ", " secret ")
        assertThat(result.balance).isEqualTo("42.5")
        assertThat(result.serviceURL).isEqualTo("https://canteen.example")
        assertThat(result.canteenName).isEqualTo("School canteen")
        assertThat(server.takeRequest().path).isEqualTo("/en/prihlasit-se?jidelna")
        val login = server.takeRequest()
        assertThat(login.path).isEqualTo("/api/login")
        assertThat(login.getHeader("Cookie")).contains("canteen=ready")
        val payload = GradeyJson.parseToJsonElement(login.body.readUtf8()).jsonObject
        assertThat(payload["cislo"]?.jsonPrimitive?.content).isEqualTo("1234")
        assertThat(payload["jmeno"]?.jsonPrimitive?.content).isEqualTo("student")
        assertThat(payload["heslo"]?.jsonPrimitive?.content).isEqualTo(" secret ")
    }

    @Test fun parsesMultipleTablesAndRestrictionsWhileSkippingPlaceholders() {
        val response = GradeyJson.parseToJsonElement("""{
          "table1": [
            {"datum":"16-09.2025","druh_popis":"Oběd 1","nazev":"Pasta","delsiPopis":"Tomato pasta","veta":"2","pocet":"1","cena":"42,50","alergeny":[["01","Cereals"]],"omezeniObj":{"den":"CO"}},
            {"datum":"15.09.2025","druh_popis":"Polévka","nazev":"Soup","delsiPopis":"Vegetable soup","veta":1,"pocet":0,"omezeniObj":{"den":""}}
          ],
          "table0": [
            {"datum":"17.09.2025","druh_popis":"Oběd 1","nazev":"Oběd 1","delsiPopis":"","veta":3},
            {"datum":"17.09.2025","druh_popis":"Oběd 1","nazev":"Holiday","delsiPopis":"Holiday","veta":4,"omezeniObj":{"den":"VP"}},
            {"datum":"31.02.2025","druh_popis":"Oběd 1","nazev":"Invalid date","delsiPopis":"Do not order","veta":5}
          ]
        }""").jsonObject
        val menu = StravaCZMenuMapper.parse(response)
        assertThat(menu.days.map { it.date }).containsExactly("2025-09-15", "2025-09-16").inOrder()
        assertThat(menu.days[0].meals[0].canModify).isFalse()
        val meal = menu.days[1].meals.single()
        assertThat(meal.ordered).isTrue()
        assertThat(meal.canModify).isFalse()
        assertThat(meal.price).isEqualTo(42.5)
        assertThat(meal.allergens).containsExactly("01 Cereals")
    }

    @Test fun submitsCorrectStagedChangeAndSavePayloads() = runTest {
        server.enqueue(MockResponse().setBody("""{"konto":"60.00"}"""))
        server.enqueue(MockResponse().setBody("""{"konto":60}"""))
        assertThat(client.changeMeal(session, 7, true)).isEqualTo("60.0")
        assertThat(client.saveOrders(session)).isEqualTo("60.0")
        val change = server.takeRequest()
        assertThat(change.path).isEqualTo("/api/pridejJidloS5")
        val body = GradeyJson.parseToJsonElement(change.body.readUtf8()).jsonObject
        assertThat(body["veta"]?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(body["pocet"]?.jsonPrimitive?.content).isEqualTo("1")
        assertThat(body["url"]?.jsonPrimitive?.content).isEqualTo(session.serviceURL)
        assertThat(server.takeRequest().path).isEqualTo("/api/saveOrders")
    }

    @Test fun reportsInsufficientBalanceEvenWithSuccessfulHttpStatus() = runTest {
        server.enqueue(MockResponse().setBody("""{"number":35,"message":"No credit"}"""))
        val error = runCatching { client.changeMeal(session, 7, true) }.exceptionOrNull()
        assertThat(error).isInstanceOf(StravaCZApiException::class.java)
        assertThat(error?.message).contains("balance is too low")
    }

    @Test fun identifiesExpiredSessionsWithoutEchoingResponseBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive provider failure"))
        val error = runCatching { client.fetchMenu(session) }.exceptionOrNull() as StravaCZApiException
        assertThat(error.authenticationRequired).isTrue()
        assertThat(error.message).doesNotContain("sensitive")
    }

    @Test fun cancellationStopsAnUnresponsiveProviderRequest() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = launch(start = CoroutineStart.UNDISPATCHED) { client.fetchMenu(session) }
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        withTimeout(1_000) { request.cancelAndJoin() }
        assertThat(request.isCancelled).isTrue()
    }

    @Test fun rejectsUnexpectedMenuInsteadOfReportingNoMeals() {
        val error = runCatching { StravaCZMenuMapper.parse(JsonObject(emptyMap())) }.exceptionOrNull()
        assertThat(error).isInstanceOf(StravaCZApiException::class.java)
    }
}
