package io.github.deedg.cineswipe.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.deedg.cineswipe.R
import io.github.deedg.cineswipe.ui.icons.CineIcons
import io.github.deedg.cineswipe.ui.theme.CineColors

/**
 * Champ de saisie aux couleurs CineSwipe. Avec [secret], le texte est masqué et un bouton
 * permet de l'afficher.
 */
@Composable
fun CineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = CineColors.TextFaint) } },
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) CineIcons.EyeOff else CineIcons.Eye,
                        contentDescription = stringResource(if (revealed) R.string.service_hide_key else R.string.service_show_key),
                        tint = CineColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret && !revealed) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CineColors.SurfaceHigh,
            unfocusedContainerColor = CineColors.SurfaceHigh,
            disabledContainerColor = CineColors.SurfaceHigh,
            focusedBorderColor = CineColors.Red,
            unfocusedBorderColor = CineColors.BorderStrong,
            focusedLabelColor = CineColors.Red,
            unfocusedLabelColor = CineColors.TextMuted,
            cursorColor = CineColors.Red,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = CineColors.TextMuted,
        ),
    )
}
