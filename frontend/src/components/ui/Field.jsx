import { useId } from 'react';

/**
 * A labelled input.
 *
 * The label sits above in sentence case at the same size as the value, because
 * a shrunken tracked-out label is decoration that costs legibility. Errors are
 * brick and stated as a sentence; hints are stone and permanent, so the layout
 * does not jump when an error appears and pushes everything down.
 */
export default function Field({
  label,
  hint,
  error,
  as = 'input',
  className = '',
  children,
  ...rest
}) {
  const id = useId();
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ');

  const frame = `w-full rounded-[2px] border bg-paper px-3 py-2.5 text-[15px] text-ink placeholder:text-stone/60 ${
    error ? 'border-brick' : 'border-rule-strong focus:border-stone'
  }`;

  const Tag = as;

  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-[14px] text-ink">
        {label}
      </label>

      <Tag
        id={id}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={describedBy || undefined}
        className={frame}
        {...rest}
      >
        {children}
      </Tag>

      {hint && !error && (
        <p id={`${id}-hint`} className="mt-1.5 text-[13px] text-stone">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="mt-1.5 text-[13px] text-brick">
          {error}
        </p>
      )}
    </div>
  );
}
