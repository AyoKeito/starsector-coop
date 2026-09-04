package coop.session;

import static coop.util.CoopText.requireText;

public record CoopPlayerInfo(String playerId, String name) {
    public CoopPlayerInfo {
        playerId = requireText(playerId, "playerId");
        name = requireText(name, "name");
    }

}
