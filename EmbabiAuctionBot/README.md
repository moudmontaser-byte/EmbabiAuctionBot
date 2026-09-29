# Embabi Auction Genius v2

Screen-driven Android overlay for the five-player auction:

1. GK - economic
2. DEF
3. CM1
4. CM2
5. ST - finish strong

Core rules:
- Total plan is 100M across all five players.
- User controls minimum acceptable rating for every slot.
- Below minimum: pass and prefer the free fallback.
- 89+ enters STAR MODE.
- GK is deliberately conservative because keeper quality is usually good.
- Final ST 88+ may spend the remaining budget. There is no benefit in hoarding money after the fifth auction.
- Opponent profiler learns cautious / balanced / aggressive bidding from actual bid increments and response speed.
- No fixed 5-6 second round timer. Round changes are inferred from screen state changes.
- Every automatic bid uses + then Confirm and must be verified from a new screen reading before any further action.
- Uncertain screen = no action / safety pause.

GUI changes are handled with an on-screen CAL procedure and Accessibility text matching for Plus/Confirm before coordinate fallback.
