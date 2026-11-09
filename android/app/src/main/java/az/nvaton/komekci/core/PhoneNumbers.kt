package az.nvaton.komekci.core

/** Phone numbers as people say and write them. Pure Kotlin, so it is unit-tested. */
object PhoneNumbers {
    private val WRITTEN = Regex("""\+?\d[\d\s\-().]{5,20}\d""")

    /** "050 123 45 67" → "994501234567", "+994 50…" → "99450…": what WhatsApp expects and what we compare. */
    fun international(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") && digits.length == 10 -> "994" + digits.drop(1)
            digits.length == 9 -> "994$digits"
            else -> digits
        }
    }

    /** The same line? Compares the last nine digits, so "+994 50 123 45 67" and "050-123-45-67" match. */
    fun same(a: String, b: String): Boolean {
        val x = international(a).takeLast(9)
        return x.length >= 7 && x == international(b).takeLast(9)
    }

    /** How the assistant reads a number back: "050 123 45 67" for Azerbaijan, groups of three otherwise. */
    fun spoken(raw: String): String {
        val n = international(raw)
        if (n.startsWith("994") && n.length == 12) {
            val local = "0" + n.drop(3)
            return "${local.take(3)} ${local.substring(3, 6)} ${local.substring(6, 8)} ${local.substring(8)}"
        }
        return "+" + n.chunked(3).joinToString(" ")
    }

    /** A number written in digits inside a sentence ("Kamranın nömrəsi 050 123 45 67"), or null. */
    fun find(text: String): String? =
        WRITTEN.findAll(text).map { it.value }.firstOrNull { it.count(Char::isDigit) in 7..15 }
            ?.let { if (it.trim().startsWith("+")) "+" + it.filter(Char::isDigit) else it.filter(Char::isDigit) }

    /**
     * A number said in words, the way people dictate it in groups: "sıfır əlli, yüz iyirmi üç, qırx beş, altmış
     * yeddi" → "0501234567"; "zero five zero one two three…" too. Digits in the text are kept. Null if it is
     * too short to be a phone number.
     */
    fun fromWords(text: String): String? {
        val out = StringBuilder()
        var group: Int? = null
        var stage = 0 // what the open group holds: 3 hundreds, 2 tens, 1 ones
        fun flush() {
            group?.let { out.append(it) }
            group = null
            stage = 0
        }
        for (k in Az.tokenize(text).map { it.key }) {
            val ones = ONES[k]
            val tens = TENS[k]
            when {
                k.isNotEmpty() && k.all(Char::isDigit) -> {
                    flush()
                    out.append(k)
                }
                k in ZERO -> {
                    flush()
                    out.append('0')
                }
                k in HUNDRED -> {
                    val g = group
                    when {
                        g == null -> group = 100
                        stage == 1 && g < 10 -> group = g * 100
                        else -> {
                            flush()
                            group = 100
                        }
                    }
                    stage = 3
                }
                tens != null -> {
                    val g = group
                    if (g != null && stage == 3) group = g + tens else {
                        flush()
                        group = tens
                    }
                    stage = 2
                }
                ones != null -> {
                    val g = group
                    if (g != null && (stage == 3 || stage == 2) && g % 10 == 0) group = g + ones else {
                        flush()
                        group = ones
                    }
                    stage = 1
                }
            }
        }
        flush()
        return out.toString().takeIf { it.length >= 7 }
    }

    private val ZERO = setOf("sifir", "zero", "oh")
    private val HUNDRED = setOf("yuz", "hundred")
    private val ONES = mapOf(
        "bir" to 1, "iki" to 2, "uc" to 3, "dord" to 4, "bes" to 5, "alti" to 6, "yeddi" to 7, "sekkiz" to 8, "doqquz" to 9,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TENS = mapOf(
        "on" to 10, "iyirmi" to 20, "otuz" to 30, "qirx" to 40, "elli" to 50, "altmis" to 60, "yetmis" to 70,
        "seksen" to 80, "doxsan" to 90,
        "ten" to 10, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )
}
