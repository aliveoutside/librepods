/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.screens.apple

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "HearingAidAdjustments"

// how long device updates are ignored after a local change, covering the debounce and the write
private const val LOCAL_CHANGE_GRACE_MS = 2500L

@Composable
fun HearingAidAdjustmentsScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val verticalScrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    val amplificationSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
    val balanceSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
    val toneSliderValue = rememberSaveable { mutableFloatStateOf(0.5f) }
    val ambientNoiseReductionSliderValue = rememberSaveable { mutableFloatStateOf(0.0f) }
    val conversationBoostEnabled = rememberSaveable { mutableStateOf(false) }
    val leftEQ = rememberSaveable { mutableStateOf(FloatArray(8)) }
    val rightEQ = rememberSaveable { mutableStateOf(FloatArray(8)) }
    val ownVoiceAmplification = rememberSaveable { mutableFloatStateOf(0.5f) }

    DisposableEffect(Unit) {
        viewModel.observeATTCharacteristic(ATTHandle.HEARING_AID)

        onDispose {
            viewModel.stopObservingATTCharacteristic()
        }
    }

    // the latest data from the AirPods, the base every write is built on
    val currentData by rememberUpdatedState(state.hearingAidData)
    var appliedData by remember { mutableStateOf<ByteArray?>(null) }
    var lastLocalChangeAt by remember { mutableLongStateOf(0L) }

    // Show what the AirPods report. The data is polled, so this runs for every poll: skip
    // unchanged data, and don't fight the user while they adjust or their write is on its way
    // (a value the AirPods didn't take shows up again with the next poll after that).
    LaunchedEffect(state.hearingAidData) {
        val data = state.hearingAidData
        if (appliedData?.contentEquals(data) == true) return@LaunchedEffect
        if (SystemClock.uptimeMillis() - lastLocalChangeAt < LOCAL_CHANGE_GRACE_MS) return@LaunchedEffect

        parseHearingAidSettingsResponse(data)?.let { parsed ->
            amplificationSliderValue.floatValue = parsed.netAmplification
            balanceSliderValue.floatValue = parsed.balance
            toneSliderValue.floatValue = parsed.leftTone
            ambientNoiseReductionSliderValue.floatValue = parsed.leftAmbientNoiseReduction
            conversationBoostEnabled.value = parsed.leftConversationBoost
            leftEQ.value = parsed.leftEQ.copyOf()
            rightEQ.value = parsed.rightEQ.copyOf()
            ownVoiceAmplification.floatValue = parsed.ownVoiceAmplification
            appliedData = data
        }
    }

    // Only changes made by the user are written, debounced while a slider is being dragged.
    val scope = rememberCoroutineScope()
    var writeJob by remember { mutableStateOf<Job?>(null) }
    fun onUserChange() {
        lastLocalChangeAt = SystemClock.uptimeMillis()
        writeJob?.cancel()
        writeJob = scope.launch {
            delay(300.milliseconds)

            val amplification = amplificationSliderValue.floatValue
            val balance = balanceSliderValue.floatValue
            val tone = toneSliderValue.floatValue
            val conversationBoost = conversationBoostEnabled.value
            val ambientNoise = ambientNoiseReductionSliderValue.floatValue

            val settings = HearingAidSettings(
                leftEQ = leftEQ.value,
                rightEQ = rightEQ.value,
                // inverse of parseHearingAidSettingsResponse (amplification = average,
                // balance = right - left), so a written value reads back the same
                leftAmplification = (amplification - balance / 2).coerceIn(-1f, 1f),
                rightAmplification = (amplification + balance / 2).coerceIn(-1f, 1f),
                leftTone = tone,
                rightTone = tone,
                leftConversationBoost = conversationBoost,
                rightConversationBoost = conversationBoost,
                leftAmbientNoiseReduction = ambientNoise,
                rightAmbientNoiseReduction = ambientNoise,
                netAmplification = amplification,
                balance = balance,
                ownVoiceAmplification = ownVoiceAmplification.floatValue
            )

            Log.d(TAG, "Updated settings: $settings")
            lastLocalChangeAt = SystemClock.uptimeMillis()
            sendHearingAidSettings(currentData, settings, viewModel::writeATTCharacteristic)
        }
    }

    StyledScaffold(
        title = stringResource(R.string.adjustments),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            StyledSlider(
                label = stringResource(R.string.amplification),
                valueRange = -1f..1f,
                value = amplificationSliderValue.floatValue,
                onValueChange = {
                    amplificationSliderValue.floatValue = it
                    onUserChange()
                },
                startImageVector = LocalIcons.current.SpeakerMin,
                endImageVector = LocalIcons.current.SpeakerMax,
                independent = true,
            )

            StyledToggle(
                label = stringResource(R.string.swipe_to_control_amplification),
                checked = state.controlStates[ControlCommandIdentifier.HPS_GAIN_SWIPE]?.getOrNull(0) == 0x01.toByte(),
                onCheckedChange = { viewModel.setControlCommand(ControlCommandIdentifier.HPS_GAIN_SWIPE, it) },
                description = stringResource(R.string.swipe_amplification_description)
            )

            StyledSlider(
                label = stringResource(R.string.balance),
                valueRange = -1f..1f,
                value = balanceSliderValue.floatValue,
                onValueChange = {
                    balanceSliderValue.floatValue = it
                    onUserChange()
                },
                snapPoints = listOf(-1f, 0f, 1f),
                startLabel = stringResource(R.string.left),
                endLabel = stringResource(R.string.right),
                independent = true,
            )

            StyledSlider(
                label = stringResource(R.string.tone),
                valueRange = -1f..1f,
                value = toneSliderValue.floatValue,
                onValueChange = {
                    toneSliderValue.floatValue = it
                    onUserChange()
                },
                startLabel = stringResource(R.string.darker),
                endLabel = stringResource(R.string.brighter),
                independent = true,
            )

            StyledSlider(
                label = stringResource(R.string.ambient_noise_reduction),
                valueRange = 0f..1f,
                value = ambientNoiseReductionSliderValue.floatValue,
                onValueChange = {
                    ambientNoiseReductionSliderValue.floatValue = it
                    onUserChange()
                },
                startLabel = stringResource(R.string.less),
                endLabel = stringResource(R.string.more),
                independent = true,
            )

            StyledToggle(
                label = stringResource(R.string.conversation_boost),
                checked = conversationBoostEnabled.value,
                onCheckedChange = {
                    conversationBoostEnabled.value = it
                    onUserChange()
                },
                description = stringResource(R.string.conversation_boost_description)
            )

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
