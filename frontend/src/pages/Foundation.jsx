import { PageHeading } from '../components/layout/AppShell';
import StatementTable from '../components/data/StatementTable';
import EmptyState from '../components/data/EmptyState';
import Amount from '../components/money/Amount';
import BalanceReveal from '../components/money/BalanceReveal';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Field from '../components/ui/Field';
import Panel from '../components/ui/Panel';
import { accounts, transactions } from '../mock/fixtures';

/**
 * A specimen of the foundation: every token and primitive on one page.
 *
 * It exists so the design can be judged before six screens are built on top of
 * it, and so a later change to a primitive can be seen everywhere at once. It
 * is not part of the product and is removed once the screens are built.
 */

function Swatch({ name, value, note }) {
  return (
    <div>
      <div className="h-14 rounded-[2px] border border-rule" style={{ backgroundColor: value }} />
      <p className="mt-2 text-[13px] text-ink">{name}</p>
      <p className="tabular text-[12px] text-stone">{value}</p>
      {note && <p className="mt-0.5 text-[12px] text-stone">{note}</p>}
    </div>
  );
}

function Section({ title, children }) {
  return (
    <section className="rule-t pt-7">
      <h2 className="mb-4 text-[19px]">{title}</h2>
      {children}
    </section>
  );
}

export default function Foundation() {
  return (
    <div className="space-y-8">
      <PageHeading
        title="Foundation"
        supporting="Tokens and primitives, before any screen is built on them."
      />

      <Section title="Palette">
        <div className="grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-6">
          <Swatch name="Ink navy" value="#0E1F33" note="Chrome, text" />
          <Swatch name="Paper" value="#FBF9F6" note="Page ground" />
          <Swatch name="Emerald" value="#1B6B4A" note="Actions, credits" />
          <Swatch name="Brass" value="#B8935A" note="Accent, sparingly" />
          <Swatch name="Stone" value="#8A6A57" note="Borders, secondary" />
          <Swatch name="Brick" value="#C0392B" note="Debits, errors only" />
        </div>
      </Section>

      <Section title="Type">
        <div className="space-y-4">
          <p className="font-display text-[clamp(2rem,5vw,3rem)] leading-[1.05] tracking-[-0.02em]">
            Banking that states the balance
          </p>
          <h3 className="text-[19px]">Fraunces carries headlines and the balance figure</h3>
          <p className="max-w-prose text-[15px] text-ink">
            Inter carries everything read as data: body copy at fifteen pixels, labels and metadata at
            thirteen. One serif voice, one sans voice, and no third.
          </p>
          <p className="max-w-prose text-[13px] text-stone">
            Secondary text is stone rather than a grey tint, so it belongs to the same warm family as
            the paper it sits on.
          </p>
          <p className="tabular text-[15px]">
            Tabular figures: 1,204.50 · 88.00 · 11,750.00 · 4,218.63
          </p>
        </div>
      </Section>

      <Section title="Money">
        <div className="grid gap-7 lg:grid-cols-[minmax(0,20rem)_1fr]">
          <Panel className="p-6">
            <p className="text-[14px] text-stone">Everyday Chequing · {accounts[0].maskedAccountNumber}</p>
            <div className="mt-2">
              <BalanceReveal value={accounts[0].balance} currency="CAD" />
            </div>
            <p className="mt-3 text-[13px] text-stone">
              The one animation in the application: this figure counts up once on mount.
            </p>
          </Panel>

          <div className="space-y-3 self-start">
            <div className="flex items-baseline justify-between gap-6">
              <span className="text-[14px] text-stone">Credit, signed</span>
              <Amount value={2640} direction="credit" signed />
            </div>
            <div className="flex items-baseline justify-between gap-6">
              <span className="text-[14px] text-stone">Debit, signed</span>
              <Amount value={142.5} direction="debit" signed />
            </div>
            <div className="flex items-baseline justify-between gap-6">
              <span className="text-[14px] text-stone">Balance, unsigned and uncoloured</span>
              <Amount value={4218.63} />
            </div>
            <div className="flex items-baseline justify-between gap-6">
              <span className="text-[14px] text-stone">Large</span>
              <Amount value={11750} size="lg" />
            </div>
          </div>
        </div>
      </Section>

      <Section title="Statement">
        <Panel className="px-5 py-4 md:px-6 md:py-5">
          <StatementTable transactions={transactions} currency="CAD" />
        </Panel>
        <p className="mt-3 text-[13px] text-stone">
          Hairline rows, amounts hard right in tabular figures. Below 768px the same rows stack rather
          than scrolling sideways.
        </p>
      </Section>

      <Section title="Controls">
        <div className="flex flex-wrap items-center gap-4">
          <Button>Confirm payment</Button>
          <Button variant="secondary">Change details</Button>
          <Button variant="quiet">View full statement</Button>
          <Button disabled>Awaiting details</Button>
        </div>

        <div className="mt-6 grid max-w-xl gap-5 sm:grid-cols-2">
          <Field label="Amount" placeholder="0.00" hint="Up to 1,000.00 per payment" />
          <Field label="Invoice reference" defaultValue="INV-2026-118" error="No invoice with that reference." />
        </div>
      </Section>

      <Section title="States">
        <div className="flex flex-wrap gap-x-8 gap-y-3">
          <Badge state="POSTED" />
          <Badge state="FUNDS_HELD" />
          <Badge state="PENDING_CONFIRMATION" />
          <Badge state="FAILED" />
          <Badge state="ACTIVE" />
          <Badge state="EXPIRED" />
        </div>

        <div className="mt-6 grid gap-5 lg:grid-cols-2">
          <Panel tone="marked" className="p-5">
            <h3 className="text-[17px]">A payment awaiting confirmation</h3>
            <p className="mt-1.5 text-[14px] text-stone">
              The brass edge marks the one region on a screen that needs a decision. It is the only
              place this colour appears at any size.
            </p>
          </Panel>

          <EmptyState
            title="No payments yet"
            supporting="Payments you make will appear here with their status."
            action={<Button variant="secondary">Pay a bill</Button>}
          />
        </div>
      </Section>
    </div>
  );
}
