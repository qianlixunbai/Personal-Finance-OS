export interface CursorPageState { currentCursor: string | null; cursorHistory: Array<string | null>; }
export interface LatestRequestGate { begin: () => number; isCurrent: (requestId: number) => boolean; invalidate: () => void; }

export function createCursorPageState(): CursorPageState { return { currentCursor: null, cursorHistory: [] }; }
export function goToNextCursorPage(state: CursorPageState, nextCursor: string): CursorPageState {
    return { currentCursor: nextCursor, cursorHistory: [...state.cursorHistory, state.currentCursor] };
}
export function goToPreviousCursorPage(state: CursorPageState): CursorPageState {
    if (state.cursorHistory.length === 0) return state;
    const cursorHistory = state.cursorHistory.slice(0, -1);
    return { currentCursor: state.cursorHistory[state.cursorHistory.length - 1], cursorHistory };
}
export function resetCursorPageState(): CursorPageState { return createCursorPageState(); }

export function createLatestRequestGate(): LatestRequestGate {
    let currentRequestId = 0;
    return {
        begin: () => ++currentRequestId,
        isCurrent: requestId => requestId === currentRequestId,
        invalidate: () => { currentRequestId += 1; },
    };
}
