@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.xueji.study

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: StudyViewModel = viewModel()
            StudyTheme(vm.data) { dark ->
                SideEffect {
                    val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, 0x33000000)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                StudyApp(vm)
            }
        }
    }
}

enum class Page(val title: String) {
    HOME("首页"), HISTORY("历史"), PROFILE("我的"), IMPORT("导入练习"),
    PAPER("练习册"), ATTEMPT("做练习"), RESULTS("练习结果"), REVIEW("错题重练"),
    CHAT("答疑"), STATS("学习统计"), GRADES("成绩记录"), TOPICS("知识点"),
    MODEL("模型设置"),
}

private val tabs = listOf(Page.HOME to Icons.Outlined.Home, Page.HISTORY to Icons.Outlined.History,
    Page.PROFILE to Icons.Outlined.Person)

@Composable
private fun StudyApp(vm: StudyViewModel) {
    var stack by rememberSaveable { mutableStateOf(arrayListOf(Page.HOME.name)) }
    val route = stack.last()
    val page = Page.valueOf(route.substringBefore('|'))
    val id = route.substringAfter('|', "")
    val parent = Page.valueOf(stack.first().substringBefore('|'))
    val root = stack.size == 1
    fun open(target: Page, argument: String = "") {
        val entry = if (argument.isEmpty()) target.name else "${target.name}|$argument"
        if (stack.last() != entry) stack = ArrayList(stack + entry)
    }
    fun back() { if (stack.size > 1) stack = ArrayList(stack.dropLast(1)) }
    fun select(tab: Page) { stack = arrayListOf(tab.name) }
    BackHandler(enabled = !root || page != Page.HOME) { if (root) select(Page.HOME) else back() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        val notice = vm.notice
        if (notice.isNotEmpty()) {
            snackbar.showSnackbar(notice, withDismissAction = true)
            if (vm.notice == notice) vm.dismissNotice()
        }
    }
    var launchFile by remember { mutableStateOf<((Imported) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = launchFile
        launchFile = null
        if (uri != null && callback != null) vm.import(uri, callback)
    }
    var importRequest: ((Imported) -> Unit) -> Unit = { receive ->
        launchFile = receive
        picker.launch(arrayOf("application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/*", "image/*", "application/octet-stream"))
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime), vm::export)
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.DOCX.mime), vm::export)
    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.DOC.mime), vm::export)
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.TEXT.mime), vm::export)
    var export by remember { mutableStateOf<Pair<String, String>?>(null) }
    export?.let { (title, content) ->
        AlertDialog(onDismissRequest = { export = null }, title = { Text("导出资料") }, text = {
            Column {
                ExportFormat.entries.forEach { format ->
                    TextButton(onClick = {
                        export = null
                        val request = ExportRequest(title, content, format)
                        vm.pendingExport = request
                        when (format) {
                            ExportFormat.PDF -> savePdf.launch(request.fileName())
                            ExportFormat.DOCX -> saveDocx.launch(request.fileName())
                            ExportFormat.DOC -> saveDoc.launch(request.fileName())
                            ExportFormat.TEXT -> saveTxt.launch(request.fileName())
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = vm.busy.isBlank()) {
                        Text(when (format) {
                            ExportFormat.PDF -> "PDF 文档"
                            ExportFormat.DOCX -> "Word 文档（DOCX）"
                            ExportFormat.DOC -> "Word 97–2003 文档（DOC）"
                            ExportFormat.TEXT -> "纯文本（TXT）"
                        })
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { export = null }) { Text("取消") } })
    }
    var confirmModel by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val requestModel: (String, () -> Unit) -> Unit = { description, action ->
        if (!vm.hasKey) {
            vm.show("请先保存模型 API 密钥")
            open(Page.MODEL)
        } else confirmModel = description to action
    }
    confirmModel?.let { (description, action) ->
        AlertDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text("发送给模型") },
            text = { Text("$description\n\n服务地址：${vm.data.ai.endpoint}\n服务商可能按使用量计费。") },
            confirmButton = {
                Button(onClick = { confirmModel = null; action() }) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { confirmModel = null }) { Text("取消") } },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                    Spacer(Modifier.height(32.dp))
                    tabs.forEach { (tab, image) ->
                        NavigationRailItem(selected = parent == tab, onClick = { select(tab) },
                            icon = { Icon(image, null) }, label = { Text(tab.title) })
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    if (!root) {
                        val title = if (page == Page.CHAT) vm.data.threads.firstOrNull { it.id == id }?.mode ?: page.title else page.title
                        TopAppBar(title = { Text(title) }, navigationIcon = {
                            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                        })
                    }
                },
                bottomBar = {
                    if (root && !wide) {
                        NavigationBar {
                            tabs.forEach { (tab, image) ->
                                NavigationBarItem(selected = page == tab, onClick = { select(tab) },
                                    icon = { Icon(image, null) }, label = { Text(tab.title) })
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (vm.busy.isNotBlank()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(vm.busy, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    if (vm.storageError.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.storageError, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = vm::retryStorage) { Text("重试") }
                            }
                        }
                    }
                    if (!vm.ready) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else AnimatedContent(route, modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "页面") { current ->
                        val currentPage = Page.valueOf(current.substringBefore('|'))
                        val currentId = current.substringAfter('|', "")
                        fun chat(mode: String) { open(Page.CHAT, vm.openThread(mode)) }
                        fun practice(paper: Paper) { open(Page.ATTEMPT, vm.start(paper)) }
                        val onExport: (String, String) -> Unit = { title, body -> export = title to body }
                        key(current) {
                            when (currentPage) {
                                Page.HOME -> HomeScreen(vm.data, { open(Page.STATS) }, { open(Page.TOPICS) },
                                    { open(Page.IMPORT) }, { chat("单题答疑") }, { open(Page.REVIEW) },
                                    { chat("资源生成") }, { open(Page.PAPER, it.id) }, { open(Page.ATTEMPT, it.id) })
                                Page.HISTORY -> HistoryScreen(vm, { open(Page.ATTEMPT, it.id) },
                                    { open(Page.RESULTS, it.id) }, { open(Page.CHAT, it.id) })
                                Page.PROFILE -> ProfileScreen(vm, { open(Page.MODEL) }, { open(Page.GRADES) },
                                    { open(Page.STATS) }, { open(Page.TOPICS) })
                                Page.IMPORT -> ImportScreen(vm, importRequest, requestModel) { paper ->
                                    back()
                                    open(Page.PAPER, paper.id)
                                }
                                Page.PAPER -> vm.data.papers.firstOrNull { it.id == currentId }?.let { paper ->
                                    PaperScreen(paper, { practice(paper) }, { vm.resetDraft(paper); open(Page.IMPORT) })
                                } ?: Missing()
                                Page.ATTEMPT -> vm.data.attempts.firstOrNull { it.id == currentId }?.let { attempt ->
                                    if (attempt.finished == null) AttemptScreen(vm, attempt) {
                                        vm.finish(attempt.id)
                                        back()
                                        open(Page.RESULTS, attempt.id)
                                    } else Missing("练习已经交卷")
                                } ?: Missing()
                                Page.RESULTS -> vm.data.attempts.firstOrNull { it.id == currentId }?.let { attempt ->
                                    ResultsScreen(vm, attempt, requestModel, onExport,
                                        onSimilar = { open(Page.ATTEMPT, it) },
                                        onDiscuss = { item ->
                                            val thread = vm.openThread("逐题讨论", item.question.prompt.take(25),
                                                attempt.subject, attempt.level, itemContext(attempt, item),
                                                "discussion-${attempt.id}-${item.question.id}")
                                            open(Page.CHAT, thread)
                                        })
                                } ?: Missing()
                                Page.REVIEW -> ReviewScreen(vm) { open(Page.ATTEMPT, it) }
                                Page.CHAT -> vm.data.threads.firstOrNull { it.id == currentId }?.let {
                                    ChatScreen(vm, it, importRequest, requestModel, onExport)
                                } ?: Missing()
                                Page.STATS -> StatsScreen(vm.data, { open(Page.GRADES) }, { open(Page.TOPICS) },
                                    requestModel, vm::analyzeTopics)
                                Page.GRADES -> GradesScreen(vm)
                                Page.TOPICS -> TopicsScreen(vm, requestModel) { topic ->
                                    val thread = vm.openThread("单题答疑", topic, context = "想复习的知识点：$topic")
                                    open(Page.CHAT, thread)
                                }
                                Page.MODEL -> SettingsScreen(vm)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyTheme(data: StudyData, content: @Composable (Boolean) -> Unit) {
    val app = LocalContext.current
    val dark = when (data.theme) { "深色" -> true; "浅色" -> false; else -> isSystemInDarkTheme() }
    val colors = when {
        data.dynamicColors && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(app) else dynamicLightColorScheme(app)
        dark -> darkColorScheme(
            primary = Color(0xFFA6D2B5), onPrimary = Color(0xFF103726),
            primaryContainer = Color(0xFF2B503C), onPrimaryContainer = Color(0xFFC4EED0),
            secondary = Color(0xFFC5CEA9), secondaryContainer = Color(0xFF434B30),
            onSecondaryContainer = Color(0xFFE3EBC4), tertiary = Color(0xFFDDC0EA),
            tertiaryContainer = Color(0xFF503C5C), onTertiaryContainer = Color(0xFFF5D8FF),
            surface = Color(0xFF161C17), onSurface = Color(0xFFE1E7DE),
            surfaceContainer = Color(0xFF202823), surfaceContainerHigh = Color(0xFF28322A),
            surfaceContainerLow = Color(0xFF1C241E), onSurfaceVariant = Color(0xFFBBC9BE),
            outlineVariant = Color(0xFF45544A),
        )
        else -> lightColorScheme(
            primary = Color(0xFF315945), onPrimary = Color.White,
            primaryContainer = Color(0xFFDCEFE0), onPrimaryContainer = Color(0xFF224834),
            secondary = Color(0xFF555E3E), secondaryContainer = Color(0xFFE6ECCF),
            onSecondaryContainer = Color(0xFF343D20), tertiary = Color(0xFF675272),
            tertiaryContainer = Color(0xFFECE3F5), onTertiaryContainer = Color(0xFF4D3A58),
            surface = Color(0xFFF8F8F3), onSurface = Color(0xFF1B261E),
            surfaceContainer = Color(0xFFECEFE6),
            surfaceContainerLow = Color(0xFFF1F3EC), surfaceContainerHigh = Color(0xFFE7EDE5),
            onSurfaceVariant = Color(0xFF4E5E53), outlineVariant = Color(0xFFCDD7CE),
        )
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = { content(dark) })
}

@Composable
fun ScrollPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 960.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
        )
    }
}

@Composable
fun Missing(label: String = "找不到这条记录") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label) }
}
