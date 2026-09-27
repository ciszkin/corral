package dev.corral.ui.architecture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseMviViewModelTest {
    private object Inc : MviIntent
    private data class St(val n: Int = 0) : MviState
    private object Ticked : MviEffect
    private object First : MviEffect
    private object Second : MviEffect

    private class Vm(scope: CoroutineScope) : BaseMviViewModel<MviIntent, St, MviEffect>(scope) {
        override fun initialState() = St()
        override fun onIntent(intent: MviIntent) { reduceState { copy(n = n + 1) }; sendEffect(Ticked) }
    }

    private class EffectVm(scope: CoroutineScope) : BaseMviViewModel<MviIntent, St, MviEffect>(scope) {
        override fun initialState() = St()
        override fun onIntent(intent: MviIntent) { sendEffect(First); sendEffect(Second) }
    }

    @Test fun intentReducesStateAndEmitsEffect() = runTest {
        val vm = Vm(backgroundScope)
        vm.onIntent(Inc); vm.onIntent(Inc)
        assertEquals(2, vm.state.value.n)
        assertEquals(Ticked, vm.effect.first())
    }

    @Test fun effectsKeepFifoOrder() = runTest {
        val vm = EffectVm(backgroundScope)
        vm.onIntent(Inc)
        assertEquals(listOf(First, Second), vm.effect.take(2).toList())
    }
}
