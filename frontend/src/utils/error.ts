export function getErrorMessage(error: unknown, fallback: string) {
    const response = (error as { response?: { data?: { message?: string } } }).response;
    const message = response?.data?.message;
    return message && message.trim() ? message : fallback;
}

export function getErrorCode(error: unknown) {
    return (error as { response?: { data?: { errorCode?: string } } }).response?.data?.errorCode;
}
