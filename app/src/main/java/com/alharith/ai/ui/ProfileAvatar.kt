package com.alharith.ai.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alharith.ai.data.ProfilePhoto
import com.alharith.ai.data.Prefs

@Composable
fun ProfileAvatar(size: Dp) {
    LaunchedEffect(Prefs.serverUrl, Prefs.serverToken) { runCatching { ProfilePhoto.load() } }
    val observed by ProfilePhoto.image.collectAsState()
    val photo = ProfilePhoto.current()
    Box(Modifier.size(size).clip(CircleShape).background(HarithColors.Surface).border(1.dp, Luxe.Gold, CircleShape), contentAlignment = Alignment.Center) {
        if (photo != null && observed != null) Image(photo.asImageBitmap(), "الصورة الشخصية", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(Prefs.userName.take(1).ifBlank { "ر" }, color = HarithColors.Fg, style = MaterialTheme.typography.titleLarge)
    }
}
