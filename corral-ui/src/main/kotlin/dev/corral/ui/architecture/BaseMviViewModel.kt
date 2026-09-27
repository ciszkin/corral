package dev.corral.ui.architecture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

abstract class BaseMviViewModel<I : MviIntent, S : MviState, E : MviEffect>(protected val vmScope: CoroutineScope) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<S> = _state.asStateFlow()
    private val _effect = Channel<E>(Channel.UNLIMITED)
    val effect: Flow<E> = _effect.receiveAsFlow()

    abstract fun initialState(): S
    abstract fun onIntent(intent: I)

    protected fun reduceState(reducer: S.() -> S) { _state.update { it.reducer() } }
    protected fun sendEffect(e: E) { _effect.trySend(e) }
}
