package com.watchchess.controller.model

/**
 * Represents the full UI state for the chess move input screen.
 *
 * @param currentInput The move string currently being built by the user (e.g., "Ne").
 * @param moveHistory  The ordered list of moves that have already been sent/confirmed.
 */
data class MoveInputState(
    val currentInput: String = "",
    val moveHistory: List<String> = emptyList()
) {
    /**
     * Returns true when there is at least one character in the current input buffer.
     */
    val hasInput: Boolean get() = currentInput.isNotEmpty()

    /**
     * Appends [char] to the current input and returns the updated state.
     */
    fun append(char: String): MoveInputState = copy(currentInput = currentInput + char)

    /**
     * Removes the last character from the current input (backspace behaviour).
     */
    fun backspace(): MoveInputState = if (currentInput.isEmpty()) this
    else copy(currentInput = currentInput.dropLast(1))

    /**
     * Clears the current input without adding it to history.
     */
    fun clear(): MoveInputState = copy(currentInput = "")

    /**
     * Sends the current input: adds it to history and resets the input buffer.
     * Does nothing if [currentInput] is blank.
     */
    fun send(): MoveInputState = if (currentInput.isBlank()) this
    else copy(
        moveHistory = moveHistory + currentInput.trim(),
        currentInput = ""
    )
}
