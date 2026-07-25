package com.voicetoinvoice.app.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TermInterpretation(
    val canonicalTerm: String,
    val quantityValue: Double,
    val confidence: Float = 0.90f
)

data class AiParsedUtterance(
    val itemName: String,
    val quantity: Double,
    val unit: String,
    val price: Double
)

sealed class TermInterpretationResult {
    data class Success(val rawTerm: String, val interpretation: TermInterpretation) : TermInterpretationResult()
    data class Error(val message: String) : TermInterpretationResult()
}

open class TermInterpreterClient {

    private val kiranaDomainContext = """
        You are an intelligent AI listing manager for an Indian Kirana Store and Sabji Mandi (Vegetable Vendor) shopkeeper.
        Your task is to parse spoken voice transcripts of retail transactions accurately into structured items, quantities, units, and total prices.
        
        Domain Vocabulary & Conversions:
        - Common Sabji/Vegetables: Pyaz (Onion), Tamatar (Tomato), Aaloo (Potato), Bhindi, Adrak (Ginger), Mirchi (Chilli), Nimbu (Lemon), Dhaniya, Palak, Gobhi, Lauki, Karela, Baingan, Gajar, Matar, Kheera, Lahsun.
        - Common Kirana/Dairy: Milk (Amul Gold/Taaza/Saras), Curd/Dahi, Paneer, Butter, Ghee (Desi Ghee), Sugar, Atta, Rice, Dal (Toor/Chana/Moong), Oil (Fortune/Mustard), Tea, Coffee, Biscuit (Parle-G/Good Day/Bourbon), Maggi, Eggs.
        - Colloquial Hindi Units & Phonetic Mishearings:
          - "kilo", "kg", "ke log" -> Unit: KG
          - "paao", "pao" -> Quantity: 0.25, Unit: KG
          - "aadha kilo", "adha kilo" -> Quantity: 0.5, Unit: KG
          - "sawa kilo" -> Quantity: 1.25, Unit: KG
          - "dhai kilo", "dhai" -> Quantity: 2.5, Unit: KG
          - "packet", "pouch" -> Unit: PACKET
          - "litre", "liter" -> Unit: LITRE
          - "piece", "nag" -> Unit: PIECE
          - "dozen", "darjan" -> Unit: DOZEN
        - Speech Recognition Error Correction Examples:
          - "18 ke log ghee" -> Item: "Desi Ghee", Quantity: 18, Unit: "KG"
          - "do kilo tamatar" -> Item: "Tamatar", Quantity: 2, Unit: "KG"
          - "aadha kilo adrak" -> Item: "Adrak", Quantity: 0.5, Unit: "KG"
          - "50 rs aaloo" -> Item: "Aaloo", Price: 50
    """.trimIndent()

    open suspend fun interpretFullUtterance(fullUtterance: String, catalogNames: List<String> = emptyList()): AiParsedUtterance? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${SupabaseConfig.SUPABASE_URL}/functions/v1/term-interpret")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("full_utterance", fullUtterance)
                    put("domain_context", kiranaDomainContext)
                    put("role", "Indian Kirana Store & Sabji Mandi Shopkeeper Seller")
                    if (catalogNames.isNotEmpty()) {
                        put("catalog_names", JSONArray(catalogNames))
                    }
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val root = JSONObject(responseText)
                    val parsedObj = root.optJSONObject("parsed")
                    if (parsedObj != null) {
                        val name = parsedObj.optString("item_name", fullUtterance)
                        val qty = parsedObj.optDouble("quantity", 1.0)
                        val unitStr = parsedObj.optString("unit", "PACKET")
                        val priceVal = parsedObj.optDouble("price", 0.0)

                        AiParsedUtterance(
                            itemName = name,
                            quantity = if (qty > 0) qty else 1.0,
                            unit = unitStr,
                            price = priceVal
                        )
                    } else null
                } else null
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Parses continuous audio transcript containing one or multiple sales spoken in rapid succession into a list of structured items.
     */
    open suspend fun interpretMultiItemUtterance(fullUtterance: String, catalogNames: List<String> = emptyList()): List<AiParsedUtterance> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${SupabaseConfig.SUPABASE_URL}/functions/v1/term-interpret")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("full_utterance", fullUtterance)
                    put("parse_mode", "multi_item")
                    put("domain_context", kiranaDomainContext)
                    put("instructions", "Parse all sales spoken in this continuous transcript into a JSON array of items.")
                    if (catalogNames.isNotEmpty()) {
                        put("catalog_names", JSONArray(catalogNames))
                    }
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val root = JSONObject(responseText)
                    val itemsArray = root.optJSONArray("parsed_items")
                    val result = mutableListOf<AiParsedUtterance>()
                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val obj = itemsArray.getJSONObject(i)
                            result.add(
                                AiParsedUtterance(
                                    itemName = obj.optString("item_name", "Unrecognized Item"),
                                    quantity = obj.optDouble("quantity", 1.0),
                                    unit = obj.optString("unit", "PACKET"),
                                    price = obj.optDouble("price", 0.0)
                                )
                            )
                        }
                    }
                    if (result.isNotEmpty()) result else emptyList()
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * 3-Pass Verification Guard: Deep Triple-Check re-analysis specifically for suspicious or unrecognized items.
     */
    open suspend fun reanalyzeAmbiguousSale(rawTranscript: String, catalogNames: List<String>): AiParsedUtterance? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${SupabaseConfig.SUPABASE_URL}/functions/v1/term-interpret")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("full_utterance", rawTranscript)
                    put("is_reanalysis", true)
                    put("verification_pass", 3)
                    put("thinking_context", "3-Pass Verification Guard: Double-check if this item is a valid Indian Kirana/Sabji Mandi term or a phonetic mishearing.")
                    put("domain_context", kiranaDomainContext)
                    put("role", "Indian Kirana & Sabji Mandi Shopkeeper Expert")
                    if (catalogNames.isNotEmpty()) {
                        put("catalog_names", JSONArray(catalogNames))
                    }
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val root = JSONObject(responseText)
                    val parsedObj = root.optJSONObject("parsed")
                    if (parsedObj != null) {
                        val name = parsedObj.optString("item_name", rawTranscript)
                        val qty = parsedObj.optDouble("quantity", 1.0)
                        val unitStr = parsedObj.optString("unit", "PACKET")
                        val priceVal = parsedObj.optDouble("price", 0.0)

                        AiParsedUtterance(
                            itemName = name,
                            quantity = if (qty > 0) qty else 1.0,
                            unit = unitStr,
                            price = priceVal
                        )
                    } else null
                } else null
            } catch (e: Exception) {
                null
            }
        }

    open suspend fun interpretTerm(rawTerm: String, contextText: String = "", shopId: String = "default_shop"): TermInterpretationResult =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${SupabaseConfig.SUPABASE_URL}/functions/v1/term-interpret")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("raw_term", rawTerm)
                    put("context", contextText)
                    put("shop_id", shopId)
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val root = JSONObject(responseText)
                    val interpObj = root.optJSONObject("interpretation")
                    val canonical = interpObj?.optString("canonical_term", "UNKNOWN") ?: "UNKNOWN"
                    val qty = interpObj?.optDouble("quantity_value", 1.0) ?: 1.0

                    TermInterpretationResult.Success(
                        rawTerm = rawTerm,
                        interpretation = TermInterpretation(
                            canonicalTerm = canonical,
                            quantityValue = qty
                        )
                    )
                } else {
                    TermInterpretationResult.Error("Server returned code $responseCode: $responseText")
                }
            } catch (e: Exception) {
                TermInterpretationResult.Error("Network error: ${e.localizedMessage}")
            }
        }

    suspend fun confirmTermAlias(rawTerm: String, canonicalValue: String, shopId: String = "default_shop"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("${SupabaseConfig.SUPABASE_URL}/functions/v1/term-interpret")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("Content-Type", "application/json")
                }

                val payload = JSONObject().apply {
                    put("raw_term", rawTerm)
                    put("shop_id", shopId)
                    put("confirm_action", JSONObject().apply {
                        put("canonical_value", canonicalValue)
                    })
                }

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                connection.responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                false
            }
        }
}
