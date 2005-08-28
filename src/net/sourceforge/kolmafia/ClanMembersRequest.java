/**
 * Copyright (c) 2005, KoLmafia development team
 * http://kolmafia.sourceforge.net/
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  [1] Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *  [2] Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in
 *      the documentation and/or other materials provided with the
 *      distribution.
 *  [3] Neither the name "KoLmafia development team" nor the names of
 *      its contributors may be used to endorse or promote products
 *      derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.sourceforge.kolmafia;

import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class ClanMembersRequest extends KoLRequest
{
	private String clanID;
	private String clanName;
	private boolean isLookup;

	public ClanMembersRequest( KoLmafia client )
	{
		super( client, "showclan.php" );
		this.clanID = "";
		this.clanName = "";
		this.isLookup = true;
	}

	public ClanMembersRequest( KoLmafia client, Object [] rankChange, Object [] newRanks, Object [] titleChange, Object [] newTitles, Object [] boots )
	{
		super( client, "clan_members.php" );
		this.isLookup = false;

		addFormField( "pwd", client.getPasswordHash() );
		addFormField( "action", "modify" );
		addFormField( "begin", "0" );

		for ( int i = 0; i < rankChange.length; ++i )
			addFormField( "level" + client.getPlayerID( (String) rankChange[i] ), (String) newRanks[i] );

		for ( int i = 0; i < titleChange.length; ++i )
			addFormField( "title" + client.getPlayerID( (String) titleChange[i] ), (String) newTitles[i] );

		for ( int i = 0; i < boots.length; ++i )
		{
			client.getClanManager().unregisterMember( (String) boots[i] );
			addFormField( "boot" + client.getPlayerID( (String) boots[i] ), "on" );
		}
	}

	public void run()
	{
		if ( isLookup )
			lookupClanData();
		else
			changeClanData();
	}

	private void lookupClanData()
	{
		// First, you need to know which clan you
		// belong to.  This is done by doing a
		// profile lookup on yourself.

		updateDisplay( DISABLED_STATE, "Determining clan ID..." );
		ProfileRequest clanIDLookup = new ProfileRequest( client, client.getCharacterData().getUsername() );
		clanIDLookup.run();

		Matcher clanIDMatcher = Pattern.compile( "showclan\\.php\\?whichclan=(\\d+)\">(.*?)</a>" ).matcher( clanIDLookup.responseText );
		if ( !clanIDMatcher.find() )
		{
			updateDisplay( ERROR_STATE, "Your character does not belong to a clan." );
			return;
		}

		// Now that you know which clan you belong
		// to, you can do a clan lookup to get a
		// complete list of clan members in one hit

		this.clanID = clanIDMatcher.group(1);
		this.clanName = clanIDMatcher.group(2);

		addFormField( "whichclan", clanID );
		updateDisplay( DISABLED_STATE, "Retrieving clan member list..." );
		super.run();

		// Now, parse out the complete list of clan
		// members so you can act on it.

		int lastMatchIndex = 0;
		Matcher memberMatcher = Pattern.compile( "<a class=nounder href=\"showplayer\\.php\\?who=(\\d+)\">(.*?)</a>.*?<td class=small>(\\d+).*?</td>" ).matcher( responseText );

		while ( memberMatcher.find( lastMatchIndex ) )
		{
			lastMatchIndex = memberMatcher.end();

			String playerID = memberMatcher.group(1);
			String playerName = memberMatcher.group(2);
			String playerLevel = memberMatcher.group(3);

			client.registerPlayer( playerName, playerID );
			client.getClanManager().registerMember( playerName, playerLevel );
		}
	}

	private void changeClanData()
	{	super.run();
	}

	public String getClanID()
	{	return clanID;
	}

	public String getClanName()
	{	return clanName;
	}
}
