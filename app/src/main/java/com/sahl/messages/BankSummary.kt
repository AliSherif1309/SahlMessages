package com.sahl.messages

import android.content.Context
import android.provider.Telephony
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BankTx(
    val threadId: Long,
    val bank: String,
    val amount: Double,
    val currency: String,
    val isDebit: Boolean,
    val merchant: String,
    val balance: Double?,
    val date: Long,
    val body: String
)

data class BankBalance(
    val bank: String,
    val balance: Double,
    val currency: String,
    val date: Long
)

data class BankReport(
    val monthSpend: Map<String, Double>,
    val monthCredit: Map<String, Double>,
    val balances: List<BankBalance>,
    val recent: List<BankTx>
)

/** Offline bank SMS parser: extracts spending, credits, balances and CSV export. */
object BankSummary {
    private val curToken = "(qar|qr|sar|sr|aed|usd|eur|gbp|kwd|bhd|omr|ريال|ر\\.ق|ر\\.س|درهم|دولار)"
    private val amtBefore = Regex("$curToken\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val amtAfter = Regex("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*$curToken")
    private val balRegex = Regex("(?:avail\\.?\\s*bal(?:ance)?|bal(?:ance)?|الرصيد(?:\\s*المتاح)?|رصيدك|المتبقي)\\s*[:\\-]?\\s*(?:$curToken\\s*)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?:\\s*$curToken)?")

    private val debitWords = Regex("(debit|debited|purchase|spent|pos|withdraw|withdrawn|atm|paid|payment|transfer(?:red)?\\s+to|charged|خصم|مشتريات|شراء|سحب|دفع|مدفوعات|حواله|تحويل\\s+إلى|تحويل\\s+الى)")
    private val creditWords = Regex("(credit|credited|salary|deposit|deposited|received|refund|ايداع|إيداع|راتب|استرداد|تم ايداع|إضافة)")

    private val merchantRegex = Regex("(?:at|to|merchant|pos|لدى|في|عند|من)\\s+([a-z0-9\\u0600-\\u06FF &._/-]{2,32})")

    private fun normCurrency(raw: String): String = when (raw.lowercase(Locale.US)) {
        "qr", "qar", "ريال", "ر.ق" -> "QAR"
        "sr", "sar", "ر.س" -> "SAR"
        "aed", "درهم" -> "AED"
        "usd", "دولار" -> "USD"
        else -> raw.uppercase(Locale.US)
    }

    private fun parseNum(s: String): Double? = s.replace(",", "").toDoubleOrNull()

    fun analyze(c: Context, bankConversations: List<Conversation>): BankReport {
        if (bankConversations.isEmpty()) return BankReport(emptyMap(), emptyMap(), emptyList(), emptyList())
        val bankNames = bankConversations.associate { it.threadId to (it.name ?: it.address) }
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600_000L
        val threadIds = bankNames.keys.joinToString(",")
        val txs = ArrayList<BankTx>()
        val latestBalances = LinkedHashMap<String, BankBalance>()

        try {
            c.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.THREAD_ID} IN ($threadIds) AND ${Telephony.Sms.DATE}>=?",
                arrayOf(cutoff.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n++ < 1500) {
                    val tid = cur.getLong(0)
                    val body = cur.getString(1) ?: continue
                    if (Sms.otp(body) != null) continue
                    val date = cur.getLong(2)
                    val bank = bankNames[tid] ?: continue
                    val t = Norm.text(body)

                    var bal: Double? = null
                    var balCur: String? = null
                    balRegex.find(t)?.let { bm ->
                        bal = parseNum(bm.groupValues[2])
                        val c1 = bm.groupValues[1].ifEmpty { bm.groupValues[3] }
                        if (c1.isNotEmpty()) balCur = normCurrency(c1)
                    }

                    // Find transaction amount (prefer one that isn't the balance value)
                    var amount: Double? = null
                    var currency: String? = null
                    val candidates = amtBefore.findAll(t).map {
                        normCurrency(it.groupValues[1]) to parseNum(it.groupValues[2])
                    } + amtAfter.findAll(t).map {
                        normCurrency(it.groupValues[2]) to parseNum(it.groupValues[1])
                    }
                    for ((curCode, num) in candidates) {
                        if (num == null || num <= 0.0) continue
                        if (bal != null && kotlin.math.abs(num - bal!!) < 0.001) continue
                        amount = num; currency = curCode; break
                    }

                    if (bal != null && bank !in latestBalances) {
                        latestBalances[bank] = BankBalance(bank, bal!!, balCur ?: currency ?: "QAR", date)
                    }

                    if (amount == null || currency == null) continue

                    val isCredit = creditWords.containsMatchIn(t) && !debitWords.containsMatchIn(t)
                    val isDebit = debitWords.containsMatchIn(t) || !isCredit

                    val rawMerchant = merchantRegex.find(t)?.groupValues?.get(1)
                        ?.replace(Regex("\\s+(on|at|bal|balance|avail|qar|sar|aed|usd).*$"), "")
                        ?.trim()
                        ?.takeIf { it.length >= 2 }
                        ?: bank
                    val merchant = rawMerchant.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                    txs.add(BankTx(tid, bank, amount, currency, isDebit, merchant, bal, date, body))
                }
            }
        } catch (_: Exception) { }

        val zone = ZoneId.systemDefault()
        val thisMonth = YearMonth.now(zone)
        val monthSpend = LinkedHashMap<String, Double>()
        val monthCredit = LinkedHashMap<String, Double>()
        for (tx in txs) {
            val ym = YearMonth.from(Instant.ofEpochMilli(tx.date).atZone(zone))
            if (ym == thisMonth) {
                if (tx.isDebit) monthSpend[tx.currency] = (monthSpend[tx.currency] ?: 0.0) + tx.amount
                else monthCredit[tx.currency] = (monthCredit[tx.currency] ?: 0.0) + tx.amount
            }
        }

        return BankReport(monthSpend, monthCredit, latestBalances.values.toList(), txs.take(25))
    }

    fun formatMoney(currency: String, amount: Double): String =
        "$currency ${"%,.2f".format(Locale.US, amount)}"

    fun toCsv(report: BankReport): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(ZoneId.systemDefault())
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        return buildString {
            append("Date,Bank,Type,Amount,Currency,Merchant,Balance\n")
            for (tx in report.recent) {
                append(esc(fmt.format(Instant.ofEpochMilli(tx.date)))).append(',')
                append(esc(tx.bank)).append(',')
                append(if (tx.isDebit) "Debit" else "Credit").append(',')
                append("%.2f".format(Locale.US, tx.amount)).append(',')
                append(esc(tx.currency)).append(',')
                append(esc(tx.merchant)).append(',')
                append(tx.balance?.let { "%.2f".format(Locale.US, it) } ?: "")
                append('\n')
            }
        }
    }
}
