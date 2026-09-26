/**
 * Buttons.
 *
 * Three kinds and no more: the one thing this screen is for, the way back out,
 * and a quiet text action. Labels say what happens — "Confirm payment", not
 * "Continue" and never with an arrow glued to the end.
 *
 * Brass is not a button colour. It marks and it rules; a filled brass button
 * would put the accent on every screen and leave emerald with nothing to mean.
 */

const VARIANTS = {
  primary:
    'bg-emerald text-paper hover:bg-[#175b3f] active:bg-[#134b34] disabled:bg-stone/40 disabled:text-paper/70',
  secondary:
    'border border-rule-strong bg-transparent text-ink hover:border-stone hover:bg-paper-shade disabled:text-stone/60',
  quiet:
    'bg-transparent px-0 text-ink underline decoration-rule-strong decoration-1 underline-offset-4 hover:decoration-brass disabled:text-stone/60',
};

const SIZES = {
  base: 'px-4 py-2.5 text-[14px]',
  lg: 'px-5 py-3 text-[15px]',
};

export default function Button({
  children,
  variant = 'primary',
  size = 'base',
  type = 'button',
  className = '',
  ...rest
}) {
  const padding = variant === 'quiet' ? 'py-1' : SIZES[size];

  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center font-medium transition-colors duration-100 disabled:cursor-not-allowed ${VARIANTS[variant]} ${padding} ${className}`}
      {...rest}
    >
      {children}
    </button>
  );
}
