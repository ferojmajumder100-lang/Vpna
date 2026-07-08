package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.example.ui.theme.*
import com.example.util.Totp
import com.example.util.ActivationManager
import com.example.util.ActivationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBackground
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // States
    var currentUrl by remember { mutableStateOf("https://www.instagram.com") }
    var progress by remember { mutableStateOf(0f) }
    var isLoading by remember { mutableStateOf(false) }
    var isOnline by remember { mutableStateOf(true) }
    var isWebError by remember { mutableStateOf(false) }
    var webViewInstance: WebView? by remember { mutableStateOf(null) }
    var isProxyEnabled by remember { mutableStateOf(false) }
    
    // Activation States
    var activationStatus by remember { mutableStateOf<ActivationStatus?>(null) }
    var isCheckingActivation by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // 2FA Dialog States
    var showOtpDialog by remember { mutableStateOf(false) }
    var secretKeyInput by remember { mutableStateOf("") }
    var generatedCode by remember { mutableStateOf("") }
    var secondsRemaining by remember { mutableStateOf(30) }

    // Periodically check internet connectivity
    LaunchedEffect(Unit) {
        while (true) {
            isOnline = isNetworkAvailable(context)
            delay(3000L)
        }
    }

    // Dynamic epoch-synchronized timer for TOTP
    LaunchedEffect(showOtpDialog, secretKeyInput) {
        if (showOtpDialog) {
            while (true) {
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                // TOTP step size is 30s
                secondsRemaining = (30 - (currentTimeSeconds % 30)).toInt()
                
                if (secretKeyInput.isNotBlank()) {
                    generatedCode = Totp.generateTOTP(secretKeyInput, currentTimeSeconds)
                }
                delay(500L) // Refresh twice per second
            }
        }
    }

    // Live continuous activation check (every 5 seconds)
    LaunchedEffect(refreshTrigger) {
        while (true) {
            val status = withContext(Dispatchers.IO) {
                ActivationManager.checkActivation(context)
            }
            activationStatus = status
            isCheckingActivation = false
            delay(5000L)
        }
    }

    if (isCheckingActivation && activationStatus == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = AccentCyan,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "Checking Authorization...",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }
        }
    } else if (activationStatus?.isActivated == true) {
        Column(
            modifier = modifier.background(DarkBackground)
        ) {
        // 1. Premium Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(AccentCyan, RoundedCornerShape(5.dp))
                        .shadow(4.dp, RoundedCornerShape(5.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "INSTA PAID",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = " ✅",
                    fontSize = 16.sp
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Subtle Toggle Button for Proxy Mode (Completely customized to not look like proxy settings)
                Box(
                    modifier = Modifier
                        .background(
                            if (isProxyEnabled) AccentCyan.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isProxyEnabled) AccentCyan else Color.Gray.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            isProxyEnabled = !isProxyEnabled
                            setProxyConfig(context, isProxyEnabled)
                            Toast.makeText(
                                context,
                                if (isProxyEnabled) "🛡️ Data Guard Active" else "🛡️ Data Guard Disabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (isProxyEnabled) AccentCyan else Color.Gray,
                                    RoundedCornerShape(3.dp)
                                )
                        )
                        Text(
                            text = if (isProxyEnabled) "GUARD ON" else "GUARD OFF",
                            color = if (isProxyEnabled) AccentCyan else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = if (isOnline) "ONLINE" else "OFFLINE",
                    color = if (isOnline) AccentCyan else PremiumOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = if (isOnline) AccentCyan.copy(alpha = 0.5f) else PremiumOrange.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 2. Linear Progress Loading Indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.Transparent)
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = AccentCyan,
                    trackColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Main WebView Container (shares remaining vertical space)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DarkBackground)
        ) {
            if (!isOnline) {
                OfflineView(
                    onRetry = {
                        isOnline = isNetworkAvailable(context)
                        if (isOnline) {
                            isWebError = false
                            webViewInstance?.reload()
                        }
                    }
                )
            } else if (isWebError) {
                ErrorView(
                    onRetry = {
                        isWebError = false
                        webViewInstance?.reload()
                    }
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        val swipeLayout = SwipeRefreshLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setColorSchemeColors(PremiumOrange.value.toInt())
                            setProgressBackgroundColorSchemeColor(SecondaryDark.value.toInt())
                        }

                        val webView = WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    isWebError = false
                                    currentUrl = url ?: ""
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    swipeLayout.isRefreshing = false
                                    if (url != null) {
                                        currentUrl = url
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    // Main frame load errors only
                                    if (request?.isForMainFrame == true) {
                                        isWebError = true
                                        isLoading = false
                                        swipeLayout.isRefreshing = false
                                    }
                                }

                                override fun onReceivedHttpAuthRequest(
                                    view: WebView?,
                                    handler: HttpAuthHandler?,
                                    host: String?,
                                    realm: String?
                                ) {
                                    if (isProxyEnabled) {
                                        handler?.proceed("vf8C9wSEnJ00_custom_zone_SL", "2278875")
                                    } else {
                                        super.onReceivedHttpAuthRequest(view, handler, host, realm)
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                }
                            }

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                useWideViewPort = true
                                loadWithOverviewMode = true
                            }

                            loadUrl(currentUrl)
                        }

                        swipeLayout.addView(webView)
                        swipeLayout.setOnRefreshListener {
                            if (isNetworkAvailable(ctx)) {
                                isWebError = false
                                webView.reload()
                            } else {
                                swipeLayout.isRefreshing = false
                                Toast.makeText(ctx, "No internet connection", Toast.LENGTH_SHORT).show()
                            }
                        }

                        webViewInstance = webView
                        swipeLayout
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 4. Custom Bottom Panel (Exactly 200dp Height)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(SurfaceDark, DarkBackground)
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(PremiumOrange.copy(alpha = 0.5f), Color.Transparent)
                    ),
                    shape = RectangleShape
                )
                .navigationBarsPadding()
                .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Section Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ INSTA UTILITIES",
                    color = PremiumOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "SECURE CLIENT",
                    color = AccentCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .background(AccentCyan.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Non-scrollable multi-row grid layout for action buttons to fit all on one screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Button 1: Clear Data
                    ActionButton(
                        text = "🗑️ Clear",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.removeAllCookies {
                                cookieManager.flush()
                            }
                            webViewInstance?.clearCache(true)
                            webViewInstance?.clearHistory()
                            webViewInstance?.clearFormData()
                            WebStorage.getInstance().deleteAllData()
                            isWebError = false
                            webViewInstance?.reload()
                            Toast.makeText(context, "✅ Data Cleared Successfully!", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // Button 2: Copy Cookies
                    ActionButton(
                        text = "📋 Cookies",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cookieManager = CookieManager.getInstance()
                            val cookies = cookieManager.getCookie(currentUrl)
                            if (!cookies.isNullOrEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("cookies", cookies)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "✅ Cookies Copied!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No cookies found for current page", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // Button 3: 2FA Settings
                    ActionButton(
                        text = "🔐 2fa",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isOnline) {
                                isWebError = false
                                webViewInstance?.loadUrl("https://accountscenter.instagram.com/password_and_security/two_factor/")
                            } else {
                                Toast.makeText(context, "Internet Offline", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Button 4: Email Settings
                    ActionButton(
                        text = "📧 Email",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isOnline) {
                                isWebError = false
                                webViewInstance?.loadUrl("https://accountscenter.instagram.com/youraccount/contact_points/?entrypoint=profile_page&is_from_dialog=true")
                            } else {
                                Toast.makeText(context, "Internet Offline", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // Button 5: Get 2FA Code
                    ActionButton(
                        text = "⏰ 2fa Code",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showOtpDialog = true
                        }
                    )
                }
            }
            
            // Instruction Tip
            Text(
                text = "Swipe down on the web view above to refresh any page.",
                color = Color.Gray,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }

    // 5. 2FA Generation Custom Dialog
    if (showOtpDialog) {
        AlertDialog(
            onDismissRequest = { showOtpDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOtpDialog = false }) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text(
                    text = "⏰ 2FA Authenticator",
                    color = PremiumOrange,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Generate offline verification codes instantly by typing or pasting your 2FA Secret Key.",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    OutlinedTextField(
                        value = secretKeyInput,
                        onValueChange = { secretKeyInput = it },
                        label = { Text("Enter Secret Key") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PremiumOrange,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = PremiumOrange,
                            unfocusedLabelColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (generatedCode.isNotBlank()) {
                        // Interactive digital neon card displaying the live TOTP code
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SecondaryDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("totp_code", generatedCode)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "✅ Code Copied!", Toast.LENGTH_SHORT).show()
                                },
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Your 6-Digit Code (Tap to Copy):",
                                    color = Color.LightGray.copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = generatedCode,
                                    color = AccentCyan,
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 4.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = { secondsRemaining / 30f },
                                        color = PremiumOrange,
                                        trackColor = Color.DarkGray,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Regenerating in ${secondsRemaining}s",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                shadowElevation = 4f
                            }
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(PremiumOrange, PremiumRed)
                                ),
                                shape = RoundedCornerShape(25.dp)
                            )
                            .clickable {
                                if (secretKeyInput.isNotBlank()) {
                                    generatedCode = Totp.generateTOTP(secretKeyInput)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("totp_code", generatedCode)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "✅ Code Copied!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please enter a secret key first", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡ Get Code",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }
    } else {
        ActivationLockScreen(
            status = activationStatus,
            deviceId = ActivationManager.getDeviceId(context),
            onRefresh = {
                isCheckingActivation = true
                refreshTrigger++
            }
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFFF7A00), // Vibrant Orange
                        Color(0xFFFF007A), // Hot Pink
                        Color(0xFF7A00FF)  // Deep Purple
                    )
                ),
                shape = RoundedCornerShape(25.dp)
            )
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(25.dp),
                clip = false
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
fun OfflineView(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "📡 Device Offline",
            color = PremiumOrange,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Please check your internet connection and try again.",
            color = Color.LightGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.horizontalGradient(listOf(PremiumOrange, PremiumRed)),
                    shape = RoundedCornerShape(25.dp)
                )
                .clickable { onRetry() }
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text("🔄 Retry Connection", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ErrorView(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠️ Connection Failed",
            color = PremiumOrange,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Could not load the requested page. It might be blocked or unavailable.",
            color = Color.LightGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.horizontalGradient(listOf(PremiumOrange, PremiumRed)),
                    shape = RoundedCornerShape(25.dp)
                )
                .clickable { onRetry() }
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text("🔄 Reload Webpage", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

fun setProxyConfig(context: Context, enabled: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
        val executor = java.util.concurrent.Executor { command -> command.run() }
        if (enabled) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("change5.owlproxy.com:7778")
                .addDirect()
                .build()
            try {
                ProxyController.getInstance().setProxyOverride(proxyConfig, executor, Runnable {
                    // Proxy has been successfully applied override
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                ProxyController.getInstance().clearProxyOverride(executor, Runnable {
                    // Proxy has been successfully cleared
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun ActivationLockScreen(
    status: ActivationStatus?,
    deviceId: String,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title or Custom Decorative Header
        Text(
            text = "🔒 DEVICE NOT ACTIVATED",
            color = PremiumOrange,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Card displaying explanation and instructions
        Card(
            colors = CardDefaults.cardColors(containerColor = SecondaryDark),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Authorization is required to use this application. Please register your Unique Device ID with the administrator.",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                if (status != null && status.message.isNotBlank()) {
                    Text(
                        text = "Status: ${status.message}",
                        color = if (status.message.contains("expired", ignoreCase = true)) PremiumRed else PremiumOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Gray.copy(alpha = 0.2f))
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Your Unique Device ID:",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Display Box with Copy Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkBackground, RoundedCornerShape(10.dp))
                            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("device_id", deviceId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "✅ Device ID Copied!", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = deviceId,
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = "📋 Copy",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pulse Live Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(AccentCyan, RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Auto-checking status every 5s...",
                color = Color.LightGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Manual check button
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFF7A00),
                            Color(0xFFFF007A),
                            Color(0xFF7A00FF)
                        )
                    ),
                    shape = RoundedCornerShape(25.dp)
                )
                .clickable { onRefresh() }
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text(
                text = "🔄 Refresh Status Now",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )
        }
    }
}


