export interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    size: number;
}
