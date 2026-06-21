package com.pocketfinancer.ui.onboarding

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Handle completed state from ViewModel
    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.COMPLETED) {
            onComplete()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.checkPermissions()
        
        val readSmsGranted = result[Manifest.permission.READ_SMS] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val receiveSmsGranted = result[Manifest.permission.RECEIVE_SMS] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        
        val smsGranted = readSmsGranted && receiveSmsGranted
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (smsGranted) {
            if (!notifGranted) {
                viewModel.showNotificationWarning()
            } else {
                viewModel.setStep(OnboardingStep.DOWNLOAD_SLM)
            }
        } else {
            viewModel.incrementPermissionDeny()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3_Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillGridModifier()
                    .weight(1f)
            ) {
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300))).togetherWith(
                            fadeOut(animationSpec = tween(300))
                        )
                    },
                    label = "OnboardingContent"
                ) { step ->
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomeStepScreen(
                            onNext = { viewModel.setStep(OnboardingStep.PERMISSIONS) }
                        )
                        OnboardingStep.PERMISSIONS -> PermissionsStepScreen(
                            deniedCount = state.deniedCount,
                            showNotificationWarning = state.showNotificationWarning,
                            onGrant = {
                                val permissions = mutableListOf(
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.RECEIVE_SMS
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                permissionLauncher.launch(permissions.toTypedArray())
                            },
                            onProceedAnyway = { viewModel.proceedAnyway() },
                            onNotNow = { viewModel.incrementPermissionDeny() }
                        )
                        OnboardingStep.DOWNLOAD_SLM -> DownloadSlmStepScreen(
                            selectedSlm = state.selectedSlm,
                            downloadState = state.downloadState,
                            isDownloading = state.isDownloading,
                            onDownload = { viewModel.downloadModel() }
                        )
                        OnboardingStep.SYNCING -> SyncingStepScreen(
                            progress = state.syncProgress,
                            message = state.syncMessage,
                            loadError = state.modelLoadError,
                            logs = state.syncLogs,
                            etaSeconds = state.syncEtaSeconds,
                            totalMessages = state.syncTotalMessages,
                            transactionalCount = state.syncTransactionalCount,
                            parsedCount = state.syncParsedCount,
                            spendsTotal = state.syncSpendsTotal,
                            recentTransactions = state.syncRecentTransactions
                        )
                        OnboardingStep.COMPLETED -> Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStepScreen(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                M3_Primary.copy(alpha = 0.3f),
                                M3_Primary
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .border(
                        BorderStroke(1.dp, M3_Primary.copy(alpha = 0.2f)),
                        RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "₹",
                    color = M3_OnPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Your money,\nyour business. Period.",
                color = M3_OnSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Experience unparalleled financial clarity with privacy-first, on-device AI. No cloud uploads. Total control over your transaction history.",
                color = M3_OnSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Info rows
            InfoRowItem(
                icon = Icons.Rounded.Shield,
                title = "Private by Design",
                desc = "Your messages never leave this device."
            )

            Spacer(modifier = Modifier.height(14.dp))

            InfoRowItem(
                icon = Icons.Rounded.Memory,
                title = "Local SLM Engine",
                desc = "Small Language Model parses SMS locally."
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = M3_Primary,
                    contentColor = M3_OnPrimary
                )
            ) {
                Text(
                    text = "Get Started",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRowItem(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3_SurfaceContainerLow, RoundedCornerShape(16.dp))
            .border(
                BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f)),
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = M3_Primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column {
            Text(
                text = title,
                color = M3_OnSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                color = M3_OnSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PermissionsStepScreen(
    deniedCount: Int,
    showNotificationWarning: Boolean,
    onGrant: () -> Unit,
    onProceedAnyway: () -> Unit,
    onNotNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(M3_SurfaceContainerHigh, RoundedCornerShape(16.dp))
                    .border(
                        BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.4f)),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sms,
                    contentDescription = null,
                    tint = M3_Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Grant App Permissions",
                color = M3_OnSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Pocket Financer uses local AI processing and requires the following permissions to function:",
                color = M3_OnSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Permission Items explanations
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainerLow, RoundedCornerShape(16.dp))
                        .border(
                            BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sms,
                        contentDescription = null,
                        tint = M3_Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("SMS Access (Required)", color = M3_OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Used to read and parse your bank's transactional alerts locally on-device.", color = M3_OnSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainerLow, RoundedCornerShape(16.dp))
                        .border(
                            BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Notifications,
                        contentDescription = null,
                        tint = M3_Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Notifications (Optional)", color = M3_OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Shows persistent background updates during the local model download and SMS sync stages.", color = M3_OnSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (showNotificationWarning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_ErrorContainer.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .border(
                            BorderStroke(1.dp, M3_Error.copy(alpha = 0.3f)),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = M3_Error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Notification Permission Denied",
                            color = M3_OnErrorContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Model download and SMS parsing progress updates won't be available through system notifications. We promise this app never spams you with promotional alerts.",
                            color = M3_OnErrorContainer.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (deniedCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_ErrorContainer.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .border(
                            BorderStroke(1.dp, M3_Error.copy(alpha = 0.2f)),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = M3_Error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SMS Permission Required",
                            color = M3_OnErrorContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "The app cannot function without SMS access. Please allow it to proceed.",
                            color = M3_OnErrorContainer.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Safety Shield Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(M3_PosContainer.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    .border(
                        BorderStroke(1.dp, M3_Pos.copy(alpha = 0.2f)),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = M3_Pos,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "100% On-Device. No data ever leaves your device. We do not use cloud servers or trackers.",
                    color = M3_OnPosContainer,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = if (showNotificationWarning) onProceedAnyway else onGrant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = M3_Primary,
                    contentColor = M3_OnPrimary
                )
            ) {
                Text(
                    text = if (showNotificationWarning) "Proceed Anyway" else "Allow Permissions",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onNotNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = "Not Now",
                    color = M3_OnSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DownloadSlmStepScreen(
    selectedSlm: SlmTier?,
    downloadState: ModelDownloader.DownloadState,
    isDownloading: Boolean,
    onDownload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(M3_SurfaceContainerHigh, RoundedCornerShape(16.dp))
                    .border(
                        BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.4f)),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = M3_Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Build Your Private Engine",
                color = M3_OnSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "We need to download the local Small Language Model (SLM) that powers Pocket Financer.",
                color = M3_OnSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Model Spec Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(M3_SurfaceContainer, RoundedCornerShape(20.dp))
                    .border(
                        BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.35f)),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MODEL SPECS",
                        color = M3_Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "llama.cpp",
                        color = M3_OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(M3_SurfaceContainerHigh, RoundedCornerShape(10.dp))
                            .border(
                                BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = selectedSlm?.name ?: "Detecting Model...",
                        color = M3_OnSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedSlm != null) "${"%.1f".format(selectedSlm.sizeGb)}B Params" else "",
                        color = M3_OnSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = selectedSlm?.description ?: "Finding compatible model for your hardware capabilities.",
                    color = M3_OnSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Interactive Flow Graphic
            ExtractionDemoGraphic()
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            if (!isDownloading) {
                Text(
                    text = "Model size: ~${selectedSlm?.sizeMb ?: 1200}MB. This one-time download ensures your data never leaves this device.",
                    color = M3_OnSurfaceVariant,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = M3_Primary,
                        contentColor = M3_OnPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                val animatedProgress by animateFloatAsState(
                    targetValue = downloadState.progress,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "DownloadProgressAnimation"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "Downloading model...",
                        color = M3_OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (downloadState.progress >= 0.98f) "Finalizing..." else "${"%.0f".format(downloadState.downloadedMb)} / ${"%.0f".format(downloadState.totalMb)} MB",
                        color = M3_Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = M3_Primary,
                    trackColor = M3_SurfaceContainerHighest
                )

                Spacer(modifier = Modifier.height(8.dp))

                val progressPercent = (downloadState.progress * 100).toInt()
                val speedText = if (downloadState.speedMbps > 0) " • ${"%.1f".format(downloadState.speedMbps)} MB/s" else ""
                val etaText = if (downloadState.etaSeconds > 0) {
                    val mins = downloadState.etaSeconds / 60
                    val secs = downloadState.etaSeconds % 60
                    " • " + (if (mins > 0) "${mins}m ${secs}s" else "${secs}s") + " left"
                } else if (downloadState.progress > 0.01f) {
                    " • Calculating..."
                } else ""

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$progressPercent%$speedText$etaText",
                        color = M3_OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Do not close the app",
                        color = M3_OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ExtractionDemoGraphic() {
    var state by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            state = (state + 1) % 4
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .background(M3_SurfaceContainerLowest, RoundedCornerShape(20.dp))
            .border(
                BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.35f)),
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "HOW IT WORKS",
                color = M3_OnSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(animationSpec = tween(400)).togetherWith(
                        fadeOut(animationSpec = tween(400))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = "DemoExtractionContent"
            ) { showState ->
                when (showState) {
                    0 -> {
                        // Raw SMS Message block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerHigh, RoundedCornerShape(12.dp))
                                .border(
                                    BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Sms,
                                    contentDescription = null,
                                    tint = M3_Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "INCOMING SMS",
                                    color = M3_Primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "AX-HDFCBK: Dear customer, Rs. 5,000.00 debited from HDFC Bank A/C *9141 towards Amazon Pay on 14-Oct-26. Ref: 1098.",
                                color = M3_OnSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    1 -> {
                        // Extracted JSON block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerLow, RoundedCornerShape(12.dp))
                                .border(
                                    BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Memory,
                                    contentDescription = null,
                                    tint = M3_Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LOCAL AI EXTRACTION (JSON)",
                                    color = M3_Primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "{",
                                color = M3_OnSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            JsonLine("amount", "5000.0", isNumber = true)
                            JsonLine("merchant", "\"Amazon Pay\"")
                            JsonLine("account", "\"HDFC *9141\"")
                            JsonLine("type", "\"debit\"")
                            JsonLine("date", "\"14-10-2026\"", isLast = true)
                            Text(
                                text = "}",
                                color = M3_OnSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    2 -> {
                        // Encrypted DB Storage
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerHigh, RoundedCornerShape(12.dp))
                                .border(
                                    BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = M3_Pos,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SECURE DATABASE STORAGE",
                                    color = M3_Pos,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(M3_PosContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = M3_Pos,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "SQLCipher Local Storage",
                                        color = M3_OnSurface,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "AES-256 encrypted database. No cloud backups, no external server exposure.",
                                        color = M3_OnSurfaceVariant,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        // Personalized Insights & Analytics
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerHigh, RoundedCornerShape(12.dp))
                                .border(
                                    BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.TrendingUp,
                                    contentDescription = null,
                                    tint = M3_Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SMART FINANCIAL INSIGHTS",
                                    color = M3_Primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Monthly Shopping Spend", color = M3_OnSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("₹5,000 / ₹40,000", color = M3_OnSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { 0.125f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape),
                                        color = M3_Primary,
                                        trackColor = M3_SurfaceContainerHighest
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(M3_SurfaceContainer, RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lightbulb,
                                        contentDescription = null,
                                        tint = M3_Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Shopping spends are within your set budget. Great job!",
                                        color = M3_OnSurfaceVariant,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonLine(key: String, value: String, isNumber: Boolean = false, isLast: Boolean = false) {
    Row(modifier = Modifier.padding(start = 12.dp)) {
        Text(
            text = "\"$key\": ",
            color = M3_Primary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value + (if (isLast) "" else ","),
            color = if (isNumber) M3_Error else M3_OnSurface,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PipelineStepper(progress: Float) {
    val steps = listOf("Engine Init", "SMS Filter", "Parse SMS", "DB Save")
    val currentStep = when {
        progress < 0.15f -> 0
        progress < 0.25f -> 1
        progress < 0.98f -> 2
        else -> 3
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3_SurfaceContainerLow, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f)), RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, stepName ->
            val isActive = index == currentStep
            val isDone = index < currentStep
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            color = when {
                                isDone -> M3_Pos.copy(alpha = 0.2f)
                                isActive -> M3_Primary.copy(alpha = 0.2f)
                                else -> M3_SurfaceContainerHighest
                            },
                            shape = CircleShape
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                isDone -> M3_Pos
                                isActive -> M3_Primary
                                else -> M3_OutlineVariant.copy(alpha = 0.5f)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = M3_Pos,
                            modifier = Modifier.size(8.dp)
                        )
                    } else if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(M3_Primary, CircleShape)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stepName,
                    color = when {
                        isDone -> M3_OnSurfaceVariant
                        isActive -> Color.White
                        else -> M3_OnSurfaceVariant.copy(alpha = 0.5f)
                    },
                    fontSize = 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                )
            }
            if (index < steps.size - 1) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = M3_OnSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
private fun SyncingStepScreen(
    progress: Float,
    message: String,
    loadError: String?,
    logs: List<String>,
    etaSeconds: Int,
    totalMessages: Int,
    transactionalCount: Int,
    parsedCount: Int,
    spendsTotal: Double,
    recentTransactions: List<ExtractedTxPreview>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loadError != null) {
            Spacer(modifier = Modifier.height(48.dp))
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = M3_Error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Sync Failed",
                color = M3_OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = loadError,
                color = M3_Error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(16.dp))

            // 1. Progress Hub: Circular Progress Wheel
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(M3_SurfaceContainerLow, CircleShape)
                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "SyncProgressAnimation"
                )

                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    color = M3_SurfaceContainerHighest.copy(alpha = 0.4f),
                    strokeWidth = 4.dp
                )

                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    color = M3_Primary,
                    strokeWidth = 4.dp
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    val etaText = if (etaSeconds > 0) {
                        val mins = etaSeconds / 60
                        val secs = etaSeconds % 60
                        if (mins > 0) {
                            "~${mins}m ${secs}s left"
                        } else {
                            "~${secs}s left"
                        }
                    } else {
                        "SYNCING"
                    }
                    Text(
                        text = etaText,
                        color = M3_OnSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Current Action Ticker
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Stage Stepper (Pipeline Stage Tracker)
            PipelineStepper(progress = progress)

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Live Stats Grid (Spends and Parse Count Cards)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Spends Card
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(M3_SurfaceContainerLow, RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SPENDS DETECTED",
                            color = M3_OnSurfaceVariant,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Icon(
                            imageVector = Icons.Rounded.TrendingDown,
                            contentDescription = null,
                            tint = M3_Pos,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "₹${"%,.2f".format(spendsTotal)}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // AI Pipeline Card
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(M3_SurfaceContainerLow, RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI PIPELINE",
                            color = M3_OnSurfaceVariant,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = M3_Primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "$parsedCount / ${if (transactionalCount > 0) transactionalCount else "-"}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Rolling Transaction Feed
            AnimatedVisibility(
                visible = recentTransactions.isNotEmpty(),
                enter = fadeIn()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainerLow, RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "LIVE EXTRACTIONS",
                        color = M3_Primary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentTransactions.take(2).forEach { tx ->
                            key(tx.merchant + tx.amount) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(M3_SurfaceContainerHighest.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                        .animateContentSize(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(M3_Primary.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ReceiptLong,
                                            contentDescription = null,
                                            tint = M3_Primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = tx.merchant,
                                            color = M3_OnSurface,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "On-Device extraction",
                                            color = M3_OnSurfaceVariant,
                                            fontSize = 9.sp
                                        )
                                    }
                                    Text(
                                        text = "₹${"%,.2f".format(tx.amount)}",
                                        color = if (tx.type == "debit") M3_OnSurface else M3_Pos,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (recentTransactions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 6. Rotating Tips Carousel
            val techTips = remember {
                listOf(
                    "🔒 100% On-Device: Your financial data is parsed locally by the AI. It never reaches the cloud.",
                    "🚀 Hardware Accelerated: Optimized matrix operations run locally using optimized CPU instructions.",
                    "💾 AES-256 Encrypted: Extracted transactions are stored in a secure local SQLCipher database.",
                    "💡 Smart Filters: Promotional messages and personal chats are filtered out instantly, preserving privacy.",
                    "🤖 Local AI: A specialized Small Language Model extracts dates, merchants, and amounts with high accuracy."
                )
            }
            var currentTipIndex by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(5000)
                    currentTipIndex = (currentTipIndex + 1) % techTips.size
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(M3_SurfaceContainerLowest, RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = M3_Primary,
                        modifier = Modifier.size(16.dp).padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = techTips[currentTipIndex],
                        transitionSpec = {
                            fadeIn(animationSpec = tween(400)).togetherWith(
                                fadeOut(animationSpec = tween(400))
                            )
                        },
                        label = "TipsCarouselAnimation"
                    ) { tipText ->
                        Text(
                            text = tipText,
                            color = M3_OnSurfaceVariant,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 7. Raw Engine Logs Drawer (Collapsible)
            var showLogs by remember { mutableStateOf(false) }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(M3_SurfaceContainerLow, RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f)), RoundedCornerShape(12.dp))
                    .animateContentSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .clickable { showLogs = !showLogs }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF4CAF50), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RAW ENGINE LOGS",
                            color = M3_OnSurfaceVariant,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Icon(
                        imageVector = if (showLogs) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (showLogs) "Collapse" else "Expand",
                        tint = M3_OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (showLogs) {
                    Divider(color = M3_OutlineVariant.copy(alpha = 0.15f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .padding(12.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(logs.size) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    text = "▶ Awaiting pipeline connection...",
                                    color = M3_OnSurfaceVariant.copy(alpha = 0.5f),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                logs.forEach { log ->
                                    val isArrow = log.startsWith("➔") || log.contains("➔")
                                    val isError = log.startsWith("Error")
                                    val color = when {
                                        isError -> M3_Error
                                        isArrow -> M3_Primary
                                        else -> M3_OnSurfaceVariant
                                    }
                                    Text(
                                        text = log,
                                        color = color,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun Modifier.fillGridModifier(): Modifier = this
    .fillMaxWidth()
    .background(M3_Background)
