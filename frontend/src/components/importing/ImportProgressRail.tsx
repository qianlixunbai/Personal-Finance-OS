type ImportStep = 'UPLOAD' | 'MAPPING' | 'WARNING_REVIEW' | 'READY_FOR_CONFIRM';

const steps: Array<{ key: ImportStep; label: string }> = [
    { key: 'UPLOAD', label: '上传文件' }, { key: 'MAPPING', label: '映射字段' }, { key: 'WARNING_REVIEW', label: '生成预览' },
    { key: 'WARNING_REVIEW', label: '复核警告' }, { key: 'READY_FOR_CONFIRM', label: '确认导入' }, { key: 'READY_FOR_CONFIRM', label: '权威回执' },
];

export function ImportProgressRail({ active }: { active: ImportStep }) {
    const activeIndex = active === 'UPLOAD' ? 0 : active === 'MAPPING' ? 1 : active === 'WARNING_REVIEW' ? 3 : 4;
    return <ol className="import-progress" aria-label="导入进度">
        {steps.map((step, index) => <li key={`${step.label}-${index}`} className={index < activeIndex ? 'import-progress__step import-progress__step--complete' : index === activeIndex ? 'import-progress__step import-progress__step--active' : 'import-progress__step'}>
            <span aria-hidden="true">{index + 1}</span><span>{step.label}</span>
        </li>)}
    </ol>;
}
