import { useEffect, useRef, useState } from 'react';
import Amount from './Amount';

/**
 * The dashboard balance, counting up once on mount.
 *
 * This is the only animation in the application. It earns its place because the
 * balance is the one number a customer opens the app to see, and a figure that
 * settles draws the eye to itself without anything else having to move.
 *
 * It runs once per mount and never on hover, focus or re-render. If the figure
 * changes later — a payment settles while the page is open — it is set
 * directly, because animating a balance that moved on its own would suggest the
 * customer did something.
 */
export default function BalanceReveal({ value, currency = 'CAD', durationMs = 900 }) {
  const target = Number(value ?? 0);
  const [shown, setShown] = useState(target);
  const hasAnimated = useRef(false);

  useEffect(() => {
    if (hasAnimated.current) {
      setShown(target);
      return;
    }
    hasAnimated.current = true;

    // Anyone who has asked their system not to animate things gets the figure.
    const stillness = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (stillness?.matches) {
      setShown(target);
      return;
    }

    let frame;
    const started = performance.now();

    const step = (now) => {
      const progress = Math.min((now - started) / durationMs, 1);
      // Decelerating: fast enough to feel immediate, slow at the end so the
      // final figure is the part that registers.
      const eased = 1 - Math.pow(1 - progress, 3);
      setShown(target * eased);

      if (progress < 1) {
        frame = requestAnimationFrame(step);
      } else {
        setShown(target);
      }
    };

    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [target, durationMs]);

  return <Amount value={shown} currency={currency} size="display" />;
}
