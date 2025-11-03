package com.lapcevichme.bookweaver.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Пример многоразового Composable для "разворачивающегося" спойлера.
 */
@Composable
fun ExpandableSpoilerCard(
    modifier: Modifier = Modifier,
    summaryTitle: String = "Краткий пересказ (Спойлер!)",
    spoilerText: String
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            // Эта анимация плавно меняет размер самой карточки
            .animateContentSize(animationSpec = tween(300)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Toggle Spoiler"
                )
                Text(
                    text = summaryTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // `AnimatedVisibility` плавно показывает или скрывает содержимое
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = spoilerText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Пример многоразового Composable для "размытого" спойлера.
 */
@Composable
fun BlurredSpoilerText(
    modifier: Modifier = Modifier,
    spoilerText: String
) {
    var isHidden by remember { mutableStateOf(true) }
    // Определяем, какое размытие применять
    val blurRadius = if (isHidden) 10.dp else 0.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            // ИСПРАВЛЕНИЕ: Теперь это "переключатель" (toggle)
            .clickable { isHidden = !isHidden }, // Клик по блоку убирает/возвращает размытие
        contentAlignment = Alignment.Center
    ) {
        // Применяем размытие и анимацию к тексту
        Text(
            text = spoilerText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(16.dp)
                // `blur` применяет эффект размытия
                .blur(radius = blurRadius)
        )

        // Поверх размытого текста показываем подсказку
        AnimatedVisibility(visible = isHidden, modifier = Modifier.align(Alignment.Center)) {
            Text(
                text = "(Нажмите, чтобы показать спойлер)",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}


/**
 * Демонстрационный экран для обоих типов спойлеров
 */
@Composable
fun SpoilerDemoScreen() {
    val longSpoilerText = "В этой главе происходит ключевое событие: " +
            "главный герой находит древний артефакт, который " +
            "оказывается не тем, чем кажется. Он также встречает " +
            "загадочного странника, который предупреждает его об " +
            "опасности. В конце главы выясняется, что странник " +
            "на самом деле его давно потерянный брат."

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Column {
            Text(
                "Обычный текст",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "Здесь идет обычное описание, которое все могут " +
                        "видеть. Оно не содержит спойлеров и просто " +
                        "дает общий контекст происходящего в книге.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Пример 1 ---
            Text(
                "Способ 1: Разворачивающаяся карточка",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ExpandableSpoilerCard(
                spoilerText = longSpoilerText
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Пример 2 ---
            Text(
                "Способ 2: Размытый текст",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            BlurredSpoilerText(
                spoilerText = longSpoilerText
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Еще какой-то текст после спойлеров, " +
                        "чтобы показать, как они вписываются в " +
                        "общий поток.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

