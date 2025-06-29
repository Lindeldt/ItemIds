package net.runelite.client.plugins.PrayAgainstPlayer;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.kit.KitType;

public enum WeaponType {

    WEAPON_MELEE,
    WEAPON_RANGED,
    WEAPON_MAGIC,
    WEAPON_UNKNOWN;

    /**
     * PvP Heuristic for prayer switching:
     * - If weapon is magic and body is ranged, returns RANGED (anti-staffbait/fake).
     * - If weapon is ranged or melee, returns as normal.
     * - If weapon is ambiguous, but body is clear, returns that style.
     * - If both are ambiguous, returns UNKNOWN.
     */
    public static WeaponType checkWeaponOnPlayer(Client client, Player attacker)
    {
        if (attacker == null || attacker.getPlayerComposition() == null || client == null)
            return WEAPON_UNKNOWN;

        int weaponId = attacker.getPlayerComposition().getEquipmentId(KitType.WEAPON);
        int chestId = attacker.getPlayerComposition().getEquipmentId(KitType.TORSO);

        String weaponName = client.getItemDefinition(weaponId).getName().toLowerCase();
        String chestName = client.getItemDefinition(chestId).getName().toLowerCase();

        if (weaponName.equals("null") || weaponName.equals("unarmed"))
            weaponName = null;
        if (chestName.equals("null"))
            chestName = null;

        boolean weaponIsMelee = weaponName != null && matchesAny(weaponName, meleeWeaponNames);
        boolean weaponIsRanged = weaponName != null && matchesAny(weaponName, rangedWeaponNames);
        boolean weaponIsMagic = weaponName != null && matchesAny(weaponName, magicWeaponNames);

        boolean bodyIsRanged = chestName != null && matchesAny(chestName, rangedChestNames);
        boolean bodyIsMagic = chestName != null && matchesAny(chestName, magicChestNames);

        // Melee always overrides
        if (weaponIsMelee)
            return WEAPON_MELEE;
        // Ranged weapon always takes precedence
        if (weaponIsRanged)
            return WEAPON_RANGED;
        // Magic weapon logic (apply anti-bait logic)
        if (weaponIsMagic) {
            if (bodyIsRanged)
                return WEAPON_RANGED;
            if (bodyIsMagic)
                return WEAPON_MAGIC;
            return WEAPON_MAGIC;
        }
        // If weapon is ambiguous, but armor is clear
        if (bodyIsRanged)
            return WEAPON_RANGED;
        if (bodyIsMagic)
            return WEAPON_MAGIC;

        return WEAPON_UNKNOWN;
    }

    private static boolean matchesAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    // Melee weapons (partial, expand as needed)
    private static final String[] meleeWeaponNames = {
            "sword", "scimitar", "dagger", "spear", "mace", "axe", "whip", "tentacle",
            "-ket-", "-xil-", "warhammer", "halberd", "claws", "hasta", "scythe", "maul",
            "anchor", "sabre", "excalibur", "machete", "dragon hunter lance", "event rpg",
            "silverlight", "darklight", "arclight", "flail", "granite hammer", "rapier",
            "bulwark", "osmumten's fang", "gsword", "godsword"
    };

    // Ranged weapons (partial, expand as needed)
    private static final String[] rangedWeaponNames = {
            "bow", "blowpipe", "xil-ul", "knife", "dart", "thrownaxe", "chinchompa", "ballista",
            "crossbow", "xbow", "shortbow", "longbow", "crystal bow", "hand cannon"
    };

    // Magic weapons
    private static final String[] magicWeaponNames = {
            "staff", "trident", "wand", "dawnbringer", "voidwaker", "sceptre",
            "tome", "kodai", "sanguinesti", "harmonised", "swamp", "nightmare staff"
    };

    // Ranged chest pieces (comprehensive list)
    private static final String[] rangedChestNames = {
            "hardleather", "studded", "frog-leather", "shayzien", "snakeskin", "rangers'",
            "green d'hide", "spined", "gilded d'hide", "blue d'hide", "red d'hide", "mixed hide",
            "black d'hide", "blessed", "hueycoatl hide", "third-age range", "karil's", "crystal body",
            "eclipse moon", "armadyl chestplate", "morrigan's", "masori", "pernix body",
            "void knight top", "elite void top"
    };

    // Magic chest pieces (comprehensive list)
    private static final String[] magicChestNames = {
            "zamorak monk", "wizard", "black robe", "dark squall", "vestment", "ghostly",
            "moonclan", "xerician", "skeletal", "elder chaos", "lunar", "splitbark", "swampbark",
            "mystic", "enchanted", "darkness", "bloodbark", "infinity", "third age mage", "dagon'hai",
            "blue moon", "ahrim's", "virtus", "ancestral", "zuriel", "robe top", "gown", "wizard robe"
    };
}