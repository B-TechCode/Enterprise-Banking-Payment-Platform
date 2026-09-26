/**
 * Nothing to show, said plainly.
 *
 * No illustration and no centred icon in a circle. A sentence explaining why
 * the space is empty, and where useful the one action that would fill it.
 */
export default function EmptyState({ title, supporting, action }) {
  return (
    <div className="border border-dashed border-rule-strong px-6 py-10 text-center">
      <p className="text-[15px] text-ink">{title}</p>
      {supporting && <p className="mx-auto mt-1.5 max-w-sm text-[14px] text-stone">{supporting}</p>}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  );
}
