package app.xodos2.ui.drawer.menu

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
fun DrawerMenu(
    title: String,
    labels: DrawerMenuLabels,
    options: DrawerMenuOptions,
    actions: DrawerMenuActions,
    modifier: Modifier = Modifier,
    showDebianDesktop: Boolean = false,
    extraContent: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            // Container/OS Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.White.copy(alpha = 0.02f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.White.copy(alpha = 0.04f)
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 25.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-0.5).sp
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            DrawerExpandableSection(title = "Display", defaultExpanded = true) {
                DrawerPrimaryItem(
                    title = "Wayland",
                    onTap = actions.onDesktopClick,
                    onLongPress = actions.onDesktopLongPress,
                )

                if (showDebianDesktop) {
                    DrawerPrimaryItem(
                        title = "X11",
                        onTap = actions.onDebianDesktopClick,
                        onLongPress = actions.onDebianDesktopLongPress,
                    )
                }

                DrawerPrimaryItem(
                    title = "Terminal",
                    onTap = actions.onTerminalClick,
                )

                DrawerDropdownField(
                    label = "Default Display",
                    value = labels.launcherDefaultLabel,
                    options = options.launcherDefaultOptions,
                    onSelect = {
                        actions.onLauncherDefaultSelect(it)
                        actions.onCloseDrawerRequest()
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            DrawerExpandableSection(title = "Desktop", defaultExpanded = true) {
                DrawerDropdownField(
                    label = "Vulkan",
                    value = labels.desktopVulkanLabel,
                    options = options.desktopVulkanOptions,
                    onSelect = {
                        actions.onDesktopVulkanSelect(it)
                        actions.onCloseDrawerRequest()
                    },
                )

                DrawerDropdownField(
                    label = "OpenGL",
                    value = labels.desktopOpenGLLabel,
                    options = options.desktopOpenGLOptions,
                    onSelect = {
                        actions.onDesktopOpenGLSelect(it)
                        actions.onCloseDrawerRequest()
                    },
                )

                DrawerDropdownField(
                    label = "Vortek",
                    value = labels.desktopVortekLabel,
                    options = options.desktopVortekOptions,
                    onSelect = {
                        actions.onDesktopVortekSelect(it)
                        actions.onCloseDrawerRequest()
                    },
                )

                DrawerExpandableSection(title = "View", defaultExpanded = true) {
                    DrawerDropdownField(
                        label = "Mouse Mode",
                        value = labels.mouseModeLabel,
                        options = options.mouseModeOptions,
                        onSelect = {
                            actions.onMouseModeSelect(it)
                            actions.onCloseDrawerRequest()
                        },
                    )
                    DrawerDropdownField(
                        label = "Resolution",
                        value = labels.resolutionPercentLabel,
                        options = options.resolutionPercentOptions,
                        onSelect = {
                            actions.onResolutionPercentSelect(it)
                            actions.onCloseDrawerRequest()
                        },
                    )
                    DrawerDropdownField(
                        label = "Scale",
                        value = labels.scalePercentLabel,
                        options = options.scalePercentOptions,
                        onSelect = {
                            actions.onScalePercentSelect(it)
                            actions.onCloseDrawerRequest()
                        },
                    )
                }
                
                DrawerTextItem(title = "Open Keyboard", onClick = actions.onKeyboardClick)
            }

            Spacer(Modifier.height(4.dp))

            DrawerExpandableSection(title = "Terminal", defaultExpanded = true) {
                DrawerDropdownField(
                    label = "Terminal Font",
                    value = labels.terminalFontLabel,
                    options = options.terminalFontOptions,
                    onSelect = {
                        actions.onTerminalFontSelect(it)
                        actions.onCloseDrawerRequest()
                    },
                )

                DrawerDropdownField(
                    label = "Session",
                    value = labels.terminalSessionLabel,
                    options = options.terminalSessionOptions,
                    onSelect = {
                        actions.onTerminalSessionSelect(it)
                        actions.onCloseDrawerRequest()
                    },
                )
            }

            if (extraContent != null) {
                Spacer(Modifier.height(8.dp))
                extraContent()
            }
        }
    }
}

@Composable
private fun DrawerPrimaryItem(
    title: String,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.01f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.02f)
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .pointerInput(title) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress?.invoke() }
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Launch",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DrawerTextItem(
    title: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.01f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.02f)
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = "Action",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
