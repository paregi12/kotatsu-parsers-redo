package org.koitharu.kotatsu.parsers.site.madtheme.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madtheme.MadthemeParser

@MangaSourceParser("MANGAMONK", "MangaMonk", "en")
internal class MangaMonk(context: MangaLoaderContext) :
	MadthemeParser(context, MangaParserSource.MANGAMONK, "mangamonk.com")
