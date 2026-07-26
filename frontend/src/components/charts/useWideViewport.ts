import { useEffect, useState } from 'react';

export function useWideViewport() {
    const [isWideViewport, setIsWideViewport] = useState(() => typeof window !== 'undefined' && window.innerWidth >= 2200);

    useEffect(() => {
        const mediaQuery = window.matchMedia?.('(min-width: 2200px)');
        if (!mediaQuery) return;
        const update = () => setIsWideViewport(mediaQuery.matches);
        update();
        mediaQuery.addEventListener?.('change', update);
        return () => mediaQuery.removeEventListener?.('change', update);
    }, []);

    return isWideViewport;
}
