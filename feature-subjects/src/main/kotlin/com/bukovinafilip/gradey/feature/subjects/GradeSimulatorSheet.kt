package com.bukovinafilip.gradey.feature.subjects

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.GradeCalculationConfidence
import com.bukovinafilip.gradey.domain.GradeCalculationIssue
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.GradeTargetResult
import com.bukovinafilip.gradey.domain.HypotheticalGrade
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.ui.GradeySectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GradeSimulatorSheet(subject: Subject, onDismiss: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalContext.current.resources
    val prepared = remember(subject) { GradeSimulation.prepare(subject) }
    var targetMode by rememberSaveable(subject.id) { mutableStateOf(true) }
    var targetText by rememberSaveable(subject.id) { mutableStateOf("2.49") }
    var targetWeight by rememberSaveable(subject.id) { mutableIntStateOf(1) }
    val saver = remember {
        listSaver<List<HypotheticalGrade>, String>(
            save = { values -> values.map { "${it.id}|${it.value}|${it.weight}" } },
            restore = { values -> values.mapNotNull { raw ->
                val fields = raw.split('|')
                if (fields.size == 3) {
                    val value = fields[1].toDoubleOrNull()
                    val weight = fields[2].toIntOrNull()
                    if (value != null && weight != null) HypotheticalGrade(fields[0], value, weight) else null
                } else null
            } },
        )
    }
    var grades by rememberSaveable(subject.id, prepared.revision, stateSaver = saver) { mutableStateOf(emptyList<HypotheticalGrade>()) }
    val simulation = remember(prepared, grades) { GradeSimulation.simulate(prepared, grades) }
    val target = remember(prepared, targetText, targetWeight) {
        GradeSimulation.targetOptions(prepared, targetText.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN, targetWeight)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(modifier = Modifier.testTag("gradeSimulatorList"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.getString(R.string.subjects_grade_simulator), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(subject.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text(strings.getString(R.string.subjects_done)) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(targetMode, { targetMode = true }, label = { Text(strings.getString(R.string.subjects_reach_a_target)) })
                    FilterChip(!targetMode, { targetMode = false }, label = { Text(strings.getString(R.string.subjects_what_if)) })
                }
            }
            item {
                GradeySectionCard(title = strings.getString(R.string.subjects_your_starting_point)) {
                    prepared.officialAverage?.let { SimulatorValueRow(strings.getString(R.string.subjects_school_average), formatAverage(it)) }
                    SimulatorValueRow(strings.getString(R.string.subjects_calculated_average), formatAverage(prepared.calculatedAverage))
                    val confidenceLabel = when (prepared.confidence) {
                        GradeCalculationConfidence.EXACT -> strings.getString(R.string.subjects_exact_arithmetic_from_known_grades_and_weights)
                        GradeCalculationConfidence.ESTIMATED -> strings.getString(R.string.subjects_estimate_from_available_grades)
                        GradeCalculationConfidence.UNAVAILABLE -> strings.getString(R.string.subjects_not_enough_grade_data)
                    }
                    Text(confidenceLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (prepared.issues.isNotEmpty()) {
                        Text(prepared.issues.joinToString("\n") { it.explanation(strings) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(strings.getString(R.string.subjects_your_teacher_decides_the_final_grade_these), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!prepared.canSimulate) {
                item { GradeySectionCard { Text(strings.getString(R.string.subjects_a_simulation_needs_at_least_one_supported)) } }
            } else if (targetMode) {
                item {
                    GradeySectionCard(title = strings.getString(R.string.subjects_choose_your_target)) {
                        OutlinedTextField(targetText, { targetText = it.take(12) }, modifier = Modifier.fillMaxWidth(), label = { Text(strings.getString(R.string.subjects_target_average)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                            isError = target.status == GradeTargetResult.Status.INVALID_TARGET,
                            supportingText = { Text(strings.getString(R.string.subjects_from_1_00_to_5_00_results)) })
                        SimulatorWeightControl(targetWeight, { targetWeight = it })
                        Text(strings.getString(R.string.subjects_each_option_repeats_one_grade_at_the), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.getString(R.string.subjects_ways_to_get_there), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        when (target.status) {
                            GradeTargetResult.Status.OPTIONS -> target.options.forEach { option ->
                                Surface(onClick = {
                                    grades = List(option.count) { HypotheticalGrade(value = option.grade.toDouble(), weight = option.weight) }
                                    targetMode = false
                                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(strings.getString(R.string.subjects_1_d_grade_2_d, option.count, option.grade), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                            Text(strings.getString(R.string.subjects_weight_1_d_each_tap_to_try, option.weight), style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(formatAverage(option.estimatedAverage), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            GradeTargetResult.Status.ALREADY_REACHED -> Text(strings.getString(R.string.subjects_your_calculated_average_already_meets_this_target), color = MaterialTheme.colorScheme.primary)
                            GradeTargetResult.Status.UNREACHABLE -> Text(strings.getString(R.string.subjects_this_target_cannot_be_reached_with_up))
                            GradeTargetResult.Status.UNAVAILABLE -> Text(strings.getString(R.string.subjects_there_is_not_enough_recorded_grade_data))
                            GradeTargetResult.Status.INVALID_TARGET -> Text(strings.getString(R.string.subjects_enter_an_average_between_1_and_5), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                if (grades.isNotEmpty() && simulation != null) item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(strings.getString(R.string.subjects_estimated_average), style = MaterialTheme.typography.labelLarge)
                            Text(formatAverage(simulation.estimatedAverage), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                            simulation.difference?.let { difference ->
                                Text(strings.getString(R.string.subjects_1_s_from_the_calculated_average, (if (difference > 0) "+" else "") + formatAverage(difference)), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                itemsIndexed(grades, key = { _, grade -> grade.id }) { index, grade ->
                    GradeySectionCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.getString(R.string.subjects_hypothetical_mark_1_d, index + 1), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { grades = grades.filterNot { it.id == grade.id } }) {
                                HugeIcon(HugeIcons.Delete02, strings.getString(R.string.subjects_remove_hypothetical_mark_1_d, index + 1), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(5) { offset ->
                                val value = offset + 1
                                FilterChip(grade.value == value.toDouble(), {
                                    grades = grades.map { if (it.id == grade.id) it.copy(value = value.toDouble()) else it }
                                }, label = { Text(value.toString()) })
                            }
                        }
                        SimulatorWeightControl(grade.weight, { weight -> grades = grades.map { if (it.id == grade.id) it.copy(weight = weight) else it } })
                    }
                }
                item {
                    OutlinedButton(onClick = { grades = grades + HypotheticalGrade() }, enabled = grades.size < GradeSimulation.MaximumHypotheticalGrades, modifier = Modifier.fillMaxWidth()) {
                        HugeIcon(HugeIcons.Add01, null)
                        Text(strings.getString(R.string.subjects_add_hypothetical_mark_1_d_10, grades.size))
                    }
                    if (grades.isNotEmpty()) TextButton(onClick = { grades = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(strings.getString(R.string.subjects_reset_scenario)) }
                }
            }
        }
    }
}

@Composable
private fun SimulatorValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SimulatorWeightControl(weight: Int, onChange: (Int) -> Unit) {
    val strings = androidx.compose.ui.platform.LocalContext.current.resources
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(strings.getString(R.string.subjects_weight_1_d, weight), fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(weight - 1) }, enabled = weight > 1) { HugeIcon(HugeIcons.MinusSign, strings.getString(R.string.subjects_decrease_weight)) }
            Text(weight.toString(), fontWeight = FontWeight.Bold)
            IconButton(onClick = { onChange(weight + 1) }, enabled = weight < 10) { HugeIcon(HugeIcons.Add01, strings.getString(R.string.subjects_increase_weight)) }
        }
    }
}

private fun GradeCalculationIssue.explanation(strings: android.content.res.Resources): String = when (this) {
    GradeCalculationIssue.INFERRED_WEIGHTS -> strings.getString(R.string.subjects_some_weights_were_inferred_from_the_school)
    GradeCalculationIssue.MISSING_WEIGHTS -> strings.getString(R.string.subjects_missing_weights_use_a_weight_of_1)
    GradeCalculationIssue.MODIFIER_MAPPING -> strings.getString(R.string.subjects_grades_with_or_use_gradey_s_numeric)
    GradeCalculationIssue.EXCLUDED_MARKS -> strings.getString(R.string.subjects_points_and_unsupported_marks_are_excluded)
    GradeCalculationIssue.INVALID_WEIGHTS -> strings.getString(R.string.subjects_marks_with_invalid_weights_are_excluded)
    GradeCalculationIssue.DUPLICATE_MARKS -> strings.getString(R.string.subjects_duplicate_records_are_counted_once)
    GradeCalculationIssue.CONFLICTING_DUPLICATE_IDS -> strings.getString(R.string.subjects_conflicting_duplicate_records_are_excluded)
    GradeCalculationIssue.INCONSISTENT_OFFICIAL_AVERAGE -> strings.getString(R.string.subjects_the_school_average_differs_from_the_calculated)
    GradeCalculationIssue.MISSING_MARK_IDENTIFIERS -> strings.getString(R.string.subjects_some_marks_have_no_stable_identifier_from)
    GradeCalculationIssue.NO_RECORDED_GRADES -> strings.getString(R.string.subjects_no_supported_recorded_grades_are_available)
}
