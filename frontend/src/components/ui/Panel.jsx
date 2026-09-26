/**
 * A bounded region of a page: a bordered rectangle on the paper ground.
 *
 * Not a card. There is no radius beyond a hairline 2px, no shadow worth
 * noticing, and no hover state — it is furniture, not something to click. Where
 * a region needs to carry weight (a payment awaiting confirmation) it takes the
 * brass edge, which is the one place that colour appears at any size.
 */
export default function Panel({
  children,
  /** 'default' | 'marked' — marked adds the brass left edge. */
  tone = 'default',
  as: Tag = 'section',
  className = '',
  ...rest
}) {
  const edge =
    tone === 'marked'
      ? 'border-l-2 border-l-brass border-y border-r border-y-rule border-r-rule'
      : 'border border-rule';

  return (
    <Tag className={`bg-paper ${edge} rounded-[2px] shadow-panel ${className}`} {...rest}>
      {children}
    </Tag>
  );
}
