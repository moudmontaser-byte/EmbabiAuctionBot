# Embabi Auction Flex v20

Android Accessibility automation for the Embabi Games auction flow.

## Rules implemented
- Fixed order verification: **GK → CB → CM → CM → ST**
- The bot also reads the displayed position and the **1/5 ... 5/5** indicator when available. A mismatch blocks bidding.
- Each of the five player slots has its **own 3 rating ranges** and **own Max Bid**.
- Rating outside all three ranges: **Skip Player**.
- No budget guard and no opponent-budget strategy.
- Per-turn increment = **ceil(Max Bid / 5)**, but the final increment is clipped so the bot never intentionally exceeds Max Bid.
  - Max 50M → +10M per turn.
  - Max 20M → +4M per turn.
  - Max 23M → +5M steps, final step clipped to 23M.
- When the displayed bid equals Max Bid exactly: **Confirm, do not Skip**.
- Confirmation retry never presses + again.
- Skip is verified by waiting for the player/round to change and can retry if the screen did not advance.

## Automated route
Home **Play Now** → Games **Auction** → scroll to **Play** → **Find Opponent** → five auctions → wait for coaches → **View Lineups** → scroll to **Start Simulation** → wait for match end → scroll to **View Results** → result page → scroll to **Return Home** → repeat.

Repeat modes:
- Infinite
- Number of games
- Time in minutes

## One-time calibration
Open the game auction screen, show the app overlay and press **CAL**:
1. Rating region
2. Position region (GK / CB / CM / ST)
3. Current bid region
4. + button
5. Confirm Bid button
6. Skip Player button

The bot uses Accessibility state/text for navigation and turn-state confirmation. Card numbers/position are read from the calibrated screen regions. Card color is not used for decisions.
