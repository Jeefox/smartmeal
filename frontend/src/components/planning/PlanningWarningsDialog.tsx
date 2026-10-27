import { Dialog } from '../ui';
import type { PeriodView } from '../../period-planning';
import { basketSummary, warningGroups } from '../../planning-presentation';
export default function PlanningWarningsDialog({
  view,
  busy,
  onClose,
  onConfirm,
}: {
  view: PeriodView;
  busy: boolean;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog title="Проверьте меню перед сохранением" onClose={onClose}>
      <p>{basketSummary(view)}</p>
      {warningGroups(view).map((g) => (
        <section className="planning-warning-group" key={g.title}>
          <h3>{g.title}</h3>
          <ul>
            {g.messages.map((m, i) => (
              <li key={i}>{m}</li>
            ))}
          </ul>
        </section>
      ))}
      <p className="caption">
        Сохранится всё меню на выбранный период. Стоимость покупки может отличаться от оценки.
      </p>
      <div className="period-actions">
        <button className="button primary" disabled={busy} onClick={onConfirm}>
          {busy ? 'Сохраняем…' : 'Сохранить с предупреждениями'}
        </button>
        <button className="button secondary" disabled={busy} onClick={onClose}>
          Вернуться к меню
        </button>
      </div>
    </Dialog>
  );
}
