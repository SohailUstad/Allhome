package com.allhome.colourcoats.chat;

/** Where the visitor is talking to us; changes what the assistant may ask for and how it formats replies. */
public enum Channel {
    /** Zoho SalesIQ chat widget on colourcoats.com: visitor is anonymous until they share contact details. */
    ZOHO_SALESIQ,
    /** ColourCoats' own chat panel on the website (direct, not via SalesIQ); same behaviour as ZOHO_SALESIQ. */
    WEB_CHAT,
    /** Instagram DM (via SalesIQ): we only know the Instagram handle; replies must be short plain text. */
    INSTAGRAM;

    /** SalesIQ channels can transfer the live chat to an operator; our own web chat cannot. */
    public boolean supportsLiveTransfer() {
        return this != WEB_CHAT;
    }
}
