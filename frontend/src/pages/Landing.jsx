import { Link } from 'react-router-dom';
import PublicHeader from '../components/layout/PublicHeader';
import Button from '../components/ui/Button';
import Amount from '../components/money/Amount';

/**
 * The public page.
 *
 * Asymmetric on purpose: the headline occupies seven of twelve columns and the
 * specimen sits in the remaining four, offset downward. A centred hero with a
 * screenshot underneath is the shape every product page has; this one reads
 * like a page from a bank's own literature, where the type carries the weight
 * and there is no illustration at all.
 *
 * The three things below the fold are stated as sentences under a hairline
 * rule, not as three cards with icons. Nothing animates.
 */

const CAPABILITIES = [
  {
    title: 'Accounts, stated plainly',
    body: 'Balances, holds and posted movements in one statement, with the figures aligned so a column can be read down rather than deciphered row by row.',
  },
  {
    title: 'Bill payments that settle',
    body: 'Funds are held when a payment is accepted and released when it settles, so the money is where the statement says it is at every moment in between.',
  },
  {
    title: 'An assistant that proposes, never pays',
    body: 'Ask in plain language and it prepares the payment. Nothing leaves an account until you have read the amount and the biller and confirmed them yourself.',
  },
];

export default function Landing() {
  return (
    <div className="min-h-dvh bg-paper">
      <PublicHeader
        action={
          <Link to="/login">
            <Button variant="secondary">Sign in</Button>
          </Link>
        }
      />

      {/* ------------------------------------------------------------ hero */}
      <main className="px-5 md:px-10">
        <div className="grid grid-cols-1 gap-12 pt-10 pb-20 md:grid-cols-12 md:gap-10 md:pt-20 md:pb-28">
          <div className="md:col-span-7">
            <h1 className="font-display text-[clamp(2.75rem,7vw,4.75rem)] leading-[1.02] tracking-[-0.025em]">
              Everyday banking,
              <br />
              without the guesswork
            </h1>

            <p className="mt-6 max-w-[38ch] text-[17px] leading-relaxed text-ink">
              A current account, a statement you can actually read, and bill payments that tell you
              exactly where they are — accepted, held, settled or refused.
            </p>

            <div className="mt-9 flex flex-wrap items-center gap-5">
              <Link to="/login">
                <Button size="lg">Sign in to your account</Button>
              </Link>
              <span className="text-[14px] text-stone">
                Personal accounts, in Canadian dollars.
              </span>
            </div>
          </div>

          {/* The specimen: a real statement fragment rather than a device
              mockup. It is the product's actual type and rules, which is a
              stronger claim than a picture of a phone. */}
          <aside className="md:col-span-5 md:pt-16">
            <div className="border-l-2 border-l-brass border-y border-r border-y-rule border-r-rule bg-paper px-6 py-6">
              <p className="text-[13px] text-stone">Everyday Chequing · *****3456</p>
              <p className="mt-1.5">
                <Amount value={4218.63} size="lg" />
              </p>

              <ul className="mt-5">
                {[
                  ['Payroll deposit', 2640, 'credit', 'Sep 20'],
                  ['City Hydro', 142.5, 'debit', 'Sep 24'],
                  ['Northern Telecom', 68.99, 'debit', 'Sep 18'],
                ].map(([label, value, direction, date]) => (
                  <li
                    key={label}
                    className="flex items-baseline justify-between gap-4 border-b border-rule py-2.5 last:border-b-0"
                  >
                    <span className="text-[14px] text-ink">{label}</span>
                    <span className="flex items-baseline gap-4">
                      <span className="text-[13px] text-stone">{date}</span>
                      <Amount value={value} direction={direction} size="sm" signed />
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </aside>
        </div>

        {/* ---------------------------------------------------- capabilities */}
        <section className="rule-t grid grid-cols-1 gap-10 py-14 md:grid-cols-3 md:gap-12 md:py-20">
          {CAPABILITIES.map(({ title, body }) => (
            <div key={title}>
              <h2 className="text-[19px]">{title}</h2>
              <p className="mt-2.5 text-[15px] leading-relaxed text-stone">{body}</p>
            </div>
          ))}
        </section>
      </main>

      <footer className="rule-t px-5 py-8 md:px-10">
        <p className="text-[13px] text-stone">
          Meridian is a demonstration platform. Accounts and balances shown are not real money.
        </p>
      </footer>
    </div>
  );
}
