package app.xueji.study

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.util.Locale
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class Question(
    val id: String = newId(),
    val prompt: String = "",
    val answer: String = "",
    val topic: String = "",
    val explanation: String = "",
)

@Serializable
data class Paper(
    val id: String = newId(),
    val title: String = "",
    val subject: String = "数学",
    val level: String = "高中",
    val questions: List<Question> = emptyList(),
)

@Serializable
data class Assessment(
    val question: Question,
    val response: String,
    val correct: Boolean? = null,
    val feedback: String = "",
)

@Serializable
data class Attempt(
    val id: String = newId(),
    val paperId: String,
    val title: String,
    val subject: String,
    val level: String,
    val started: Long,
    val elapsedSeconds: Long = 0,
    val runningSince: Long? = null,
    val runningElapsedRealtime: Long? = null,
    val finished: Long? = null,
    val items: List<Assessment>,
) {
    fun seconds(now: Long = System.currentTimeMillis(), elapsedNow: Long = SystemClock.elapsedRealtime()): Long {
        val startedAt = runningSince ?: return elapsedSeconds
        val elapsedStart = runningElapsedRealtime
        val delta = if (elapsedStart != null && elapsedNow >= elapsedStart) elapsedNow - elapsedStart
        else now - startedAt
        return elapsedSeconds + (delta / 1000).coerceAtLeast(0)
    }
}

@Serializable
data class Message(val user: Boolean, val text: String)

@Serializable
data class Thread(
    val id: String = newId(),
    val title: String,
    val mode: String,
    val subject: String = "",
    val level: String = "",
    val context: String = "",
    val updated: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
)

@Serializable
data class Grade(
    val id: String = newId(),
    val label: String,
    val subject: String,
    val score: Double,
    val full: Double,
    val average: Double?,
    val date: String,
)

@Serializable
data class AiSettings(
    val endpoint: String = "https://api.deepseek.com",
    val model: String = "deepseek-v4-pro",
    val thinking: Boolean = false,
)

@Serializable
data class StudyData(
    val papers: List<Paper> = emptyList(),
    val attempts: List<Attempt> = emptyList(),
    val threads: List<Thread> = emptyList(),
    val grades: List<Grade> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val dynamicColors: Boolean = true,
    val theme: String = "跟随系统",
    val ai: AiSettings = AiSettings(),
)

class StudyStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("study.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): StudyData {
        if (!file.exists()) return StudyData()
        return json.decodeFromString(file.readFully().decodeToString())
    }

    fun write(data: StudyData) {
        val bytes = json.encodeToString(data).encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}

private val questionMarker = Regex(
    """^\s*(?:第\s*[0-9一二三四五六七八九十百]+\s*题[.．、:：]?\s*|[（(][0-9]{1,3}[）)]\s*|[0-9]{1,3}(?:[、)）]|[.．](?![0-9]))\s*)"""
)
private val metadata = Regex("""^\s*[【\[]?(参考答案|答案|考点|知识点|解析)[】\]]?\s*[:：]?\s*(.+)$""")
private val sectionHeading = Regex("""^\s*[一二三四五六七八九十]+[、.．].*(题|部分)\s*$""")

fun splitQuestions(raw: String): List<Question> {
    val blocks = mutableListOf<MutableList<String>>()
    val preamble = mutableListOf<String>()
    for (line in raw.lineSequence().map { it.trimEnd() }) {
        if (sectionHeading.matches(line)) continue
        val marker = questionMarker.find(line)
        if (marker != null && line.substring(marker.range.last + 1).isNotBlank()) {
            blocks += mutableListOf(line.substring(marker.range.last + 1))
        } else {
            (blocks.lastOrNull() ?: preamble).add(line)
        }
    }
    if (blocks.isEmpty() && preamble.any { it.isNotBlank() }) blocks.add(preamble)
    return blocks.mapNotNull { lines ->
        val body = mutableListOf<String>()
        var answer = ""
        var topic = ""
        var explanation = ""
        lines.forEachIndexed { index, line ->
            val match = if (index > 0) metadata.matchEntire(line) else null
            when (match?.groupValues?.get(1)) {
                "答案", "参考答案" -> answer = match.groupValues[2]
                "考点", "知识点" -> topic = match.groupValues[2]
                "解析" -> explanation = match.groupValues[2]
                else -> body += line
            }
        }
        body.joinToString("\n").trim().takeIf(String::isNotBlank)?.let {
            Question(prompt = it, answer = answer, topic = topic, explanation = explanation)
        }
    }
}

fun evaluate(question: Question, response: String): Boolean? {
    val expected = question.answer.trim()
    if (response.isBlank() || expected.isBlank()) return null
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(Regex("""\s+"""), "").lowercase(Locale.ROOT)
    val acceptable = expected.split('|', '｜').filter { it.isNotBlank() }.map(::normalize)
    if (normalize(response) in acceptable) return true

    // A mismatch for free-form or algebraic text still needs a human or rubric.
    val numeral = Regex("""[+-]?(?:\d+(?:\.\d*)?|\.\d+)""")
    val option = Regex("""[a-h]""")
    val submitted = normalize(response)
    if (acceptable.isNotEmpty() && option.matches(submitted) && acceptable.all(option::matches)) return false
    if (numeral.matches(submitted) && acceptable.isNotEmpty() && acceptable.all(numeral::matches)) {
        val value = submitted.toBigDecimal()
        return acceptable.any { it.toBigDecimal().compareTo(value) == 0 }
    }
    return null
}

val subjects = listOf("语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "政治", "其他")
val levels = listOf("小学", "初中", "高中", "大学", "其他")
val periods = listOf("今天", "本周", "本月", "今年", "全部")

fun dateOf(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

fun dateInPeriod(date: LocalDate, period: String, today: LocalDate = LocalDate.now()): Boolean {
    val start = when (period) {
        "今天" -> today
        "本周" -> today.minusDays(today.dayOfWeek.value.toLong() - 1)
        "本月" -> today.withDayOfMonth(1)
        "今年" -> today.withDayOfYear(1)
        else -> return true
    }
    return date >= start && date <= today
}

data class TopicStat(val title: String, val reviewed: Int, val missed: Int, val occurrences: Int)

fun latestAssessments(attempts: List<Attempt>): List<Pair<Attempt, Assessment>> =
    attempts.filter { it.finished != null }.sortedByDescending { it.finished }
        .flatMap { attempt -> attempt.items.map { attempt to it } }
        .distinctBy { (_, item) -> item.question.id }

fun topicStats(attempts: List<Attempt>): List<TopicStat> {
    val all = attempts.filter { it.finished != null }.flatMap(Attempt::items)
    return all.flatMap { item ->
        item.question.topic.split('、', ',', '，', ';', '；').map(String::trim)
            .filter(String::isNotBlank).distinct().map { it to item.correct }
    }.groupBy({ it.first }, { it.second })
        .map { (topic, results) -> TopicStat(topic, results.count { it != null }, results.count { it == false }, results.size) }
        .sortedWith(compareByDescending<TopicStat> { it.missed }.thenByDescending { it.occurrences }.thenBy { it.title })
}

fun durationLabel(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", safe / 3600, safe / 60 % 60, safe % 60)
}
