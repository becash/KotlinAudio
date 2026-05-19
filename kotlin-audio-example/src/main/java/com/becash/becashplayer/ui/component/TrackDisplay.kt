package com.becash.becashplayer.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.becash.becashplayer.ext.toDurationString
import com.becash.becashplayer.ui.theme.BecashPlayerTheme

@Composable
fun TrackDisplay(
    title: String,
    artist: String,
    artwork: String,
    position: Long,
    duration: Long,
    isLive: Boolean,
    trackLabel: String = "",
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        if (artwork.isNotEmpty())
            AsyncImage(
                model = artwork,
                contentDescription = "Album Cover",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(top = 48.dp)
            )
        if (artist.isNotEmpty()) {
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            if (trackLabel.isNotEmpty()) {
                Text(
                    text = "$trackLabel  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (isLive)
            Text(
                text = "Live",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
        else
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 8.dp, end = 8.dp)
            ) {
                Text(
                    text = position.toDurationString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                var isSeeking by remember { mutableStateOf(false) }
                var seekValue by remember { mutableFloatStateOf(0f) }
                val sliderValue = if (isSeeking) seekValue
                                  else if (duration == 0L) 0f
                                  else position.toFloat() / duration.toFloat()
                Slider(
                    value = sliderValue,
                    onValueChange = { isSeeking = true; seekValue = it },
                    onValueChangeFinished = {
                        onSeek((seekValue * duration).toLong())
                        isSeeking = false
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = duration.toDurationString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Preview(showBackground = true)
@Composable
fun TrackDisplayPreview() {
    BecashPlayerTheme {
        TrackDisplay(
            title = "Title",
            artist = "Artist",
            artwork = "",
            position = 1000,
            duration = 6000,
            isLive = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TrackDisplayLivePreview() {
    BecashPlayerTheme {
        TrackDisplay(
            title = "Title",
            artist = "Artist",
            artwork = "",
            position = 1000,
            duration = 6000,
            isLive = true,
        )
    }
}
