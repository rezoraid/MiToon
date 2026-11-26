package id.mitoon.app

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

class LangOption(val code: String, val label: String)

object Lang {
    val options = listOf(
        LangOption("id", "Bahasa Indonesia"),
        LangOption("en", "English"),
        LangOption("ms", "Bahasa Melayu"),
        LangOption("es", "Español"),
        LangOption("ja", "日本語")
    )
    private val columns = mapOf("en" to 1, "ms" to 2, "es" to 3, "ja" to 4)

    private class Tpl(val regex: Regex, val parts: List<String>, val len: Int)

    private var prefs: android.content.SharedPreferences? = null
    private var rows: List<List<String>> = emptyList()
    private val exact = HashMap<String, List<String>>()
    private var templates: List<Tpl> = emptyList()
    private val memo = HashMap<String, String>()

    var choice by mutableStateOf("")
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("mitoon_lang", Context.MODE_PRIVATE)
        choice = prefs?.getString("code", "") ?: ""
        rows = runCatching {
            ctx.assets.open("i18n.txt").bufferedReader().useLines { seq ->
                seq.map { it.split("||") }.filter { it.size == 5 }.map { r -> r.map { it.replace("\\n", "\n") } }.toList()
            }
        }.getOrDefault(emptyList())
        val tpls = ArrayList<Tpl>()
        for (r in rows) {
            if (r[0].contains("{}")) {
                val parts = r[0].split("{}")
                val rx = parts.joinToString("(.+?)") { Regex.escape(it) }
                tpls += Tpl(Regex("^$rx$", RegexOption.DOT_MATCHES_ALL), parts, r[0].length)
                exact["T:" + r[0]] = r
            } else exact[r[0]] = r
        }
        templates = tpls.sortedByDescending { it.len }
    }

    fun set(code: String) {
        choice = code
        prefs?.edit()?.putString("code", code)?.apply()
        memo.clear()
    }

    val effective: String
        get() {
            val c = choice
            if (c.isNotEmpty()) return c
            val sys = when (val l = Locale.getDefault().language) { "in" -> "id"; else -> l }
            return if (sys == "id" || sys in columns) sys else "en"
        }

    fun label(): String = if (choice.isEmpty()) "" else options.firstOrNull { it.code == choice }?.label ?: ""

    fun translate(text: String): String {
        val lang = effective
        val col = columns[lang] ?: return text
        if (text.isBlank()) return text
        val key = "$lang\u0000$text"
        memo[key]?.let { return it }
        val out = lookup(text, col)
        if (memo.size > 4000) memo.clear()
        memo[key] = out
        return out
    }

    private fun lookup(text: String, col: Int): String {
        exact[text]?.let { return it[col] }
        for (t in templates) {
            val m = t.regex.matchEntire(text) ?: continue
            val row = exact["T:" + t.parts.joinToString("{}")] ?: continue
            val args = m.groupValues.drop(1)
            var i = 0
            val tr = row[col]
            val sb = StringBuilder()
            var k = 0
            while (k < tr.length) {
                if (tr.startsWith("{}", k)) { sb.append(args.getOrElse(i) { "" }.let { a -> translate(a) }); i++; k += 2 } else { sb.append(tr[k]); k++ }
            }
            return sb.toString()
        }
        return text
    }
}

fun tr(text: String): String = Lang.translate(text)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, Lang.translate(msg), Toast.LENGTH_SHORT).show()

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val c = Lang.choice
    androidx.compose.material3.Text(
        Lang.translate(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing,
        textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style
    )
}
