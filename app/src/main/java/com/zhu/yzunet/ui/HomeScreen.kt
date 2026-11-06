package com.zhu.yzunet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhu.yzunet.data.AppSettings

private val CardShape = RoundedCornerShape(20.dp)
private val SuccessGreen = Color(0xFF16A34A)
private val DangerRed = Color(0xFFDC2626)

/** 宽度达到该值时切换为双栏（平板横屏 / 大窗分屏） */
private const val WIDE_WIDTH_DP = 720

/** 高度低于该值时进入紧凑模式（手机横屏 / 分屏小窗） */
private const val COMPACT_HEIGHT_DP = 420

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onRequestAutoReconnect: (Boolean) -> Unit,
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.onLaunch() }

    // LocalConfiguration 会随窗口尺寸变化重组：旋转、分屏拖动分隔条、桌面模式缩放都能实时响应
    val configuration = LocalConfiguration.current
    val wideLayout = configuration.screenWidthDp >= WIDE_WIDTH_DP
    val compactHeight = configuration.screenHeightDp < COMPACT_HEIGHT_DP

    val logHeight = if (compactHeight) 110.dp else 180.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 矮窗口里底部栏太占地方，改成把按钮内联到内容里
            if (!compactHeight) {
                LoginBar(busy = ui.busy, onLogin = vm::login)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (wideLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 1180.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HeroCard(ui = ui, onCheck = vm::checkOnly, compact = compactHeight)
                        if (compactHeight) {
                            LoginButton(
                                busy = ui.busy,
                                onLogin = vm::login,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        AccountCard(ui = ui, vm = vm)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ShortcutCard(onClick = vm::createShortcut)
                        AutomationCard(
                            ui = ui,
                            vm = vm,
                            onRequestAutoReconnect = onRequestAutoReconnect,
                        )
                        LogCard(ui = ui, onClear = vm::clearLogs, height = logHeight)
                        FooterHint()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HeroCard(ui = ui, onCheck = vm::checkOnly, compact = compactHeight)
                    if (compactHeight) {
                        LoginButton(
                            busy = ui.busy,
                            onLogin = vm::login,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AccountCard(ui = ui, vm = vm)
                    ShortcutCard(onClick = vm::createShortcut)
                    AutomationCard(
                        ui = ui,
                        vm = vm,
                        onRequestAutoReconnect = onRequestAutoReconnect,
                    )
                    LogCard(ui = ui, onClear = vm::clearLogs, height = logHeight)
                    FooterHint()
                }
            }
        }
    }
}

// ============================ 顶部状态区 ============================

private data class StatusVisual(val icon: ImageVector, val title: String)

private fun statusVisual(status: NetStatus): StatusVisual = when (status) {
    NetStatus.Idle -> StatusVisual(Icons.Rounded.WifiOff, "待检测")
    NetStatus.Checking -> StatusVisual(Icons.Rounded.Autorenew, "正在检测网络…")
    NetStatus.Online -> StatusVisual(Icons.Rounded.Wifi, "外网已连接")
    NetStatus.Offline -> StatusVisual(Icons.Rounded.WifiOff, "外网未连接")
    NetStatus.LoggingIn -> StatusVisual(Icons.Rounded.Autorenew, "正在认证…")
    NetStatus.Success -> StatusVisual(Icons.Rounded.CheckCircle, "认证成功")
    NetStatus.Failed -> StatusVisual(Icons.Rounded.ErrorOutline, "认证失败")
}

private fun heroBrush(status: NetStatus): Brush = when (status) {
    NetStatus.Online, NetStatus.Success ->
        Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF059669)))

    NetStatus.Offline ->
        Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFD97706)))

    NetStatus.Failed ->
        Brush.linearGradient(listOf(Color(0xFFF87171), Color(0xFFDC2626)))

    else ->
        Brush.linearGradient(listOf(Color(0xFF60A5FA), Color(0xFF2563EB)))
}

@Composable
private fun HeroCard(ui: UiState, onCheck: () -> Unit, compact: Boolean) {
    val visual = statusVisual(ui.status)
    val working = ui.status == NetStatus.Checking || ui.status == NetStatus.LoggingIn
    val pad = if (compact) 14.dp else 20.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(heroBrush(ui.status)),
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .offset(x = 215.dp, y = (-70).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        )

        Column(Modifier.padding(pad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "扬大校园网",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onCheck,
                    enabled = !ui.busy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "重新检测",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val badgeSize = if (compact) 40.dp else 48.dp
                Box(
                    modifier = Modifier
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            visual.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = visual.title,
                        color = Color.White,
                        fontSize = if (compact) 18.sp else 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = ui.statusDetail,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 12.sp,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!compact) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "服务",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = ui.settings.serviceName,
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ============================ 账户（始终可见、可编辑） ============================

@Composable
private fun AccountCard(ui: UiState, vm: HomeViewModel) {
    var passwordVisible by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val saved = ui.settings.hasCredentials

    SectionCard(
        title = "账户与认证",
        trailing = { SavedChip(saved = saved) },
    ) {
        OutlinedTextField(
            value = ui.settings.userId,
            onValueChange = vm::updateUserId,
            label = { Text("学工号 / 用户名") },
            placeholder = { Text("例如 1923010101") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = ui.settings.password,
            onValueChange = vm::updatePassword,
            label = { Text("校园网密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "网络服务",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )

        ServiceSelector(
            selected = ui.settings.serviceName,
            onSelect = vm::updateServiceIndex,
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "改动会自动保存到本机（Keystore 加密）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (saved) {
                TextButton(onClick = { showClearDialog = true }) {
                    Text("清除", fontSize = 12.sp, color = DangerRed)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除保存的账号？") },
            text = { Text("清除后需要重新填写学工号与密码，后台自动重连会暂停工作。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearCredentials()
                        showClearDialog = false
                    }
                ) {
                    Text("清除", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ServiceSelector(selected: String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppSettings.SERVICES.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 14.sp) },
                    onClick = {
                        onSelect(index + 1)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SavedChip(saved: Boolean) {
    val color = if (saved) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (saved) "已保存" else "未填写",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ============================ 桌面快捷方式 ============================

@Composable
private fun ShortcutCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), CardShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text("添加到桌面", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "点桌面图标直接登录，不经过本页面",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================ 自动化设置 ============================

@Composable
private fun AutomationCard(
    ui: UiState,
    vm: HomeViewModel,
    onRequestAutoReconnect: (Boolean) -> Unit,
) {
    SectionCard(title = "自动化") {
        SwitchRow(
            title = "打开应用时自动登录",
            subtitle = "启动即用已保存的账号尝试认证",
            checked = ui.settings.autoLoginOnLaunch,
            onCheckedChange = vm::setAutoLoginOnLaunch,
        )

        Spacer(Modifier.height(8.dp))

        SwitchRow(
            title = "断线自动重连",
            subtitle = "后台常驻检测，掉线自动重新登录",
            checked = ui.settings.autoReconnect,
            onCheckedChange = { enabled -> onRequestAutoReconnect(enabled) },
        )

        if (ui.settings.autoReconnect) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "检测间隔",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30, 60).forEach { seconds ->
                    val selected = ui.settings.checkIntervalSec == seconds
                    val label = "$seconds 秒"
                    if (selected) {
                        Button(
                            onClick = { vm.updateInterval(seconds) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 6.dp,
                            ),
                        ) { Text(label, fontSize = 12.sp) }
                    } else {
                        OutlinedButton(
                            onClick = { vm.updateInterval(seconds) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 6.dp,
                            ),
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = ui.settings.checkUrl,
            onValueChange = vm::updateCheckUrl,
            label = { Text("连通性检测地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ============================ 日志 ============================

@Composable
private fun LogCard(ui: UiState, onClear: () -> Unit, height: Dp) {
    val listState = rememberLazyListState()

    LaunchedEffect(ui.logs.size) {
        if (ui.logs.isNotEmpty()) {
            listState.animateScrollToItem(ui.logs.lastIndex)
        }
    }

    SectionCard(
        title = "运行日志",
        trailing = {
            TextButton(onClick = onClear, enabled = ui.logs.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("清空", fontSize = 12.sp)
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            if (ui.logs.isEmpty()) {
                item {
                    Text(
                        text = "暂无日志",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(ui.logs) { line ->
                Text(
                    text = line,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ============================ 底部登录栏 / 内联按钮 ============================

@Composable
private fun LoginBar(busy: Boolean, onLogin: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            LoginButton(
                busy = busy,
                onLogin = onLogin,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LoginButton(busy: Boolean, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onLogin,
        enabled = !busy,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(Icons.Rounded.Bolt, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (busy) "处理中…" else "一键登录校园网",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ============================ 通用容器 ============================

@Composable
private fun FooterHint() {
    Text(
        text = "首次连接校园 Wi-Fi 时，系统提示「网络无法访问互联网」属正常现象，选择保持连接即可。",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SectionCard(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), CardShape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
