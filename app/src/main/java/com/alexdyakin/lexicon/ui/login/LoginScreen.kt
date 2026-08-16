package com.alexdyakin.lexicon.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alexdyakin.lexicon.R

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onRegister: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        KenBurnsBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Logo drifts in from above on first composition
            var shown by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(900)) + slideInVertically(tween(900)) { -it / 3 },
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_lexicon),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .heightIn(max = 190.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            Spacer(Modifier.height(28.dp))

            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        "Sign in",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "One account for Lexicon, Alchemy, Pokémon and Voice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                    )

                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::onUsername,
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::onPassword,
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation =
                            if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.login(onLoggedIn) }),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "Hide" else "Show")
                            }
                        },
                    )

                    // Errors slide in rather than snapping the layout
                    AnimatedVisibility(
                        visible = state.error != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = { viewModel.login(onLoggedIn) },
                        enabled = !state.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Enter", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TextButton(onClick = onRegister, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Create an account")
                    }
                }
            }
        }
    }
}

/**
 * Slow scale/drift over the Lexicon room art so the login screen breathes
 * instead of sitting as a flat image.
 */
@Composable
private fun KenBurnsBackground() {
    val transition = rememberInfiniteTransition(label = "kenburns")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(18_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Image(
        painter = painterResource(R.drawable.bg_login),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .scale(scale),
    )

    // Darken toward the bottom so the form stays readable over any frame
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Black.copy(alpha = 0.75f),
                    )
                )
            )
    )
}
