import { ref } from 'vue'
import type { ConversationTurn } from '../types/workbench'

export function useTurnSelection() {
  const selectedTurnId = ref('')
  const followCurrent = ref(true)

  function synchronize(turns: ConversationTurn[]) {
    const latest = turns.at(-1)
    if (!latest) {
      selectedTurnId.value = ''
      followCurrent.value = true
      return
    }
    if (followCurrent.value || !turns.some(turn => turn.turnId === selectedTurnId.value)) {
      selectedTurnId.value = latest.turnId
    }
  }

  function select(turnId: string) {
    selectedTurnId.value = turnId
    followCurrent.value = false
  }

  function follow(turns: ConversationTurn[]) {
    followCurrent.value = true
    selectedTurnId.value = turns.at(-1)?.turnId ?? ''
  }

  function reset() {
    selectedTurnId.value = ''
    followCurrent.value = true
  }

  return { selectedTurnId, followCurrent, synchronize, select, follow, reset }
}
