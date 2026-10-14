package bw.agriconnect.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Standard text field with inline validation. Errors are shown beneath the field
 * and are announced to accessibility services via `supportingText` + `isError`.
 */
@Composable
fun AgriTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    helper: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    maxLines: Int = if (singleLine) 1 else 4,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            shape = MaterialTheme.shapes.small,
            supportingText = when {
                error != null -> { { Text(error, color = MaterialTheme.colorScheme.error) } }
                helper != null -> { { Text(helper, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                else -> null
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AgriColors.ForestGreen,
                unfocusedBorderColor = AgriColors.Border,
                errorBorderColor = AgriColors.ErrorRed,
                focusedLabelColor = AgriColors.ForestGreen,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Password field with a show/hide toggle. The visibility state lives here so the
 * transformation and the toggle can never disagree.
 */
@Composable
fun AgriPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Password",
    modifier: Modifier = Modifier,
    error: String? = null,
    helper: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        leadingIcon = if (error != null) {
            { Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AgriColors.ErrorRed) }
        } else {
            null
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        supportingText = when {
            error != null -> { { Text(error, color = MaterialTheme.colorScheme.error) } }
            helper != null -> { { Text(helper, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            else -> null
        },
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AgriColors.ForestGreen,
            unfocusedBorderColor = AgriColors.Border,
            errorBorderColor = AgriColors.ErrorRed,
            focusedLabelColor = AgriColors.ForestGreen,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
