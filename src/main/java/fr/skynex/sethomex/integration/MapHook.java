package fr.skynex.sethomex.integration;

import fr.skynex.sethomex.models.Home;

public interface MapHook {
    void registerHome(Home home);
    void removeHome(Home home);
    void clearAll();
}
