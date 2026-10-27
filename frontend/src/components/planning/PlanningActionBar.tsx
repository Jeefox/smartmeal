import { useEffect, useRef } from 'react';
export default function PlanningActionBar({
  ariaLabel = 'Сохранение меню',
  message,
  label,
  onAction,
  disabled,
  secondary,
}: {
  ariaLabel?: string;
  message: string;
  label: string;
  onAction: () => void;
  disabled: boolean;
  secondary?: { label: string; onClick: () => void };
}) {
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    const bar = ref.current!,
      flow = bar.closest<HTMLElement>('.planning-flow'),
      nav = document.querySelector<HTMLElement>('.bottom-nav');
    if (!flow) return;
    const measure = () => {
      flow.style.setProperty(
        '--planning-bar-height',
        `${Math.ceil(bar.getBoundingClientRect().height)}px`,
      );
      flow.style.setProperty(
        '--planning-nav-height',
        `${Math.ceil(nav?.getBoundingClientRect().height || 0)}px`,
      );
    };
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null;
    observer?.observe(bar);
    if (nav) observer?.observe(nav);
    window.addEventListener('resize', measure);
    measure();
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', measure);
      flow.style.removeProperty('--planning-bar-height');
      flow.style.removeProperty('--planning-nav-height');
    };
  }, []);
  return (
    <section className="planning-action-bar" aria-label={ariaLabel} ref={ref}>
      <p role="status">{message}</p>
      <div>
        <button className="button primary" disabled={disabled} onClick={onAction}>
          {label}
        </button>
        {secondary && (
          <button className="text-button" disabled={disabled} onClick={secondary.onClick}>
            {secondary.label}
          </button>
        )}
      </div>
    </section>
  );
}
