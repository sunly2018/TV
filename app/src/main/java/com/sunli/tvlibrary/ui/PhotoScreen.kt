package com.sunli.tvlibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sunli.tvlibrary.data.Photo
import java.io.File

@Composable
fun PhotoViewerScreen(
    photos: List<Photo>,
    initialIndex: Int,
    onExit: (Int) -> Unit
) {
    var index by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }
    BackHandler { onExit(index) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        index = (index - 1).coerceAtLeast(0)
                        true
                    }

                    Key.DirectionRight -> {
                        index = (index + 1).coerceAtMost(photos.lastIndex.coerceAtLeast(0))
                        true
                    }

                    else -> false
                }
            }
    ) {
        val photo = photos.getOrNull(index)
        if (photo != null) {
            AsyncImage(
                model = File(photo.path),
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(20.dp)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xB3000000))
                .padding(horizontal = 40.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = photo?.name ?: "",
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${index + 1} / ${photos.size}   ◀ ▶ 切换照片   返回键退出",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}
