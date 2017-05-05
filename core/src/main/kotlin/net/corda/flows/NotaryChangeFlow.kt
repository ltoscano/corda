package net.corda.flows

import net.corda.core.contracts.*
import net.corda.core.crypto.Party
import net.corda.core.flows.InitiatingFlow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.security.PublicKey

/**
 * A flow to be used for changing a state's Notary. This is required since all input states to a transaction
 * must point to the same notary.
 *
 * This assembles the transaction for notary replacement and sends out change proposals to all participants
 * of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, the transaction containing all signatures is sent back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
@InitiatingFlow
class NotaryChangeFlow<out T : ContractState>(
        originalState: StateAndRef<T>,
        newNotary: Party,
        progressTracker: ProgressTracker = tracker())
    : AbstractStateReplacementFlow.Instigator<T, T, Party>(originalState, newNotary, progressTracker) {

    override fun assembleTx(): Pair<SignedTransaction, Iterable<PublicKey>> {
        val state = originalState.state
        val tx = TransactionType.NotaryChange.Builder(originalState.state.notary)

        val participants: Iterable<PublicKey>

        if (state.encumbrance == null) {
            val modifiedState = TransactionState(state.data, modification)
            tx.addInputState(originalState)
            tx.addOutputState(modifiedState)
            participants = state.data.participants
        } else {
            participants = resolveEncumbrances(tx)
        }

        val myKey = serviceHub.legalIdentityKey
        tx.signWith(myKey)

        val stx = tx.toSignedTransaction(false)

        return Pair(stx, participants)
    }

    /**
     * Adds the notary change state transitions to the [tx] builder for the [originalState] and its encumbrance
     * state chain (encumbrance states might be themselves encumbered by other states).
     *
     * @return union of all added states' participants
     */
    private fun resolveEncumbrances(tx: TransactionBuilder): Iterable<PublicKey> {
        val stateRef = originalState.ref
        val txId = stateRef.txhash
        val issuingTx = serviceHub.storageService.validatedTransactions.getTransaction(txId)
                ?: throw StateReplacementException("Transaction $txId not found")
        val outputs = issuingTx.tx.outputs

        val participants = mutableSetOf<PublicKey>()

        var nextStateIndex = stateRef.index
        var newOutputPosition = tx.outputStates().size
        while (true) {
            val nextState = outputs[nextStateIndex]
            tx.addInputState(StateAndRef(nextState, StateRef(txId, nextStateIndex)))
            participants.addAll(nextState.data.participants)

            if (nextState.encumbrance == null) {
                val modifiedState = TransactionState(nextState.data, modification)
                tx.addOutputState(modifiedState)
                break
            } else {
                val modifiedState = TransactionState(nextState.data, modification, newOutputPosition + 1)
                tx.addOutputState(modifiedState)
                nextStateIndex = nextState.encumbrance
            }

            newOutputPosition++
        }

        return participants
    }
}
