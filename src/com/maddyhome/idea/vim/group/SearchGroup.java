package com.maddyhome.idea.vim.group;

/*
* IdeaVim - A Vim emulator plugin for IntelliJ Idea
* Copyright (C) 2003 Rick Maddy
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 2
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*/

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.TextRange;
import com.maddyhome.idea.vim.command.Command;
import com.maddyhome.idea.vim.ex.LineRange;
import com.maddyhome.idea.vim.helper.EditorHelper;
import com.maddyhome.idea.vim.helper.SearchHelper;
import com.maddyhome.idea.vim.helper.StringHelper;
import com.maddyhome.idea.vim.option.Options;
import com.maddyhome.idea.vim.regexp.CharHelper;
import com.maddyhome.idea.vim.regexp.CharPointer;
import com.maddyhome.idea.vim.regexp.CharacterClasses;
import com.maddyhome.idea.vim.regexp.RegExp;
import java.awt.Color;
import java.text.NumberFormat;
import java.text.ParsePosition;
import javax.swing.JOptionPane;

/**
 *
 */
public class SearchGroup extends AbstractActionGroup
{
    public static final int KEEP_FLAGS = 1;
    public static final int CONFIRM = 2;
    public static final int IGNORE_ERROR = 4;
    public static final int GLOBAL = 8;
    public static final int IGNORE_CASE = 16;
    public static final int NO_IGNORE_CASE = 32;
    public static final int PRINT = 64;
    public static final int REUSE = 128;

    public SearchGroup()
    {
    }

    public String getLastSearch()
    {
        return lastSearch;
    }

    public String getLastPattern()
    {
        return lastPattern;
    }

    public boolean searchAndReplace(Editor editor, DataContext context, LineRange range, String excmd, String exarg)
    {
        boolean res = true;

        CharPointer cmd = new CharPointer(new StringBuffer(exarg));
        //sub_nsubs = 0;
        //sub_nlines = 0;

        int which_pat;
        if (excmd.equals("~"))
            which_pat = RE_LAST;    /* use last used regexp */
        else
            which_pat = RE_SUBST;   /* use last substitute regexp */

        CharPointer pat = null;
        CharPointer sub = null;
        char delimiter;
        /* new pattern and substitution */
        if (excmd.charAt(0) == 's' && !cmd.isNul() && !Character.isWhitespace(cmd.charAt()) &&
            "0123456789cegriIp|\"".indexOf(cmd.charAt()) == -1)
        {
            /* don't accept alphanumeric for separator */
            if (CharacterClasses.isAlpha(cmd.charAt()))
            {
                // EMSG(_("E146: Regular expressions can't be delimited by letters")); TODO
                return false;
            }
            /*
            * undocumented vi feature:
            *  "\/sub/" and "\?sub?" use last used search pattern (almost like
            *  //sub/r).  "\&sub&" use last substitute pattern (like //sub/).
            */
            if (cmd.charAt() == '\\')
            {
                cmd.inc();
                if ("/?&".indexOf(cmd.charAt()) == -1)
                {
                    // EMSG(_(e_backslash)); TODO
                    return false;
                }
                if (cmd.charAt() != '&')
                {
                    which_pat = RE_SEARCH;      /* use last '/' pattern */
                }
                pat = new CharPointer("");             /* empty search pattern */
                delimiter = cmd.charAt();             /* remember delimiter character */
                cmd.inc();
            }
            else            /* find the end of the regexp */
            {
                which_pat = RE_LAST;            /* use last used regexp */
                delimiter = cmd.charAt();             /* remember delimiter character */
                cmd.inc();
                pat = cmd.ref(0);                      /* remember start of search pat */
                cmd = RegExp.skip_regexp(cmd, delimiter, true);
                if (cmd.charAt() == delimiter)        /* end delimiter found */
                {
                    cmd.set('\u0000').inc(); /* replace it with a NUL */
                }
            }

            /*
            * Small incompatibility: vi sees '\n' as end of the command, but in
            * Vim we want to use '\n' to find/substitute a NUL.
            */
            sub = cmd.ref(0);          /* remember the start of the substitution */

            while (!cmd.isNul())
            {
                if (cmd.charAt() == delimiter)            /* end delimiter found */
                {
                    cmd.set('\u0000').inc(); /* replace it with a NUL */
                    break;
                }
                if (cmd.charAt(0) == '\\' && cmd.charAt(1) != 0)  /* skip escaped characters */
                {
                    cmd.inc();
                }
                cmd.inc();
            }
        }
        else        /* use previous pattern and substitution */
        {
            if (lastReplace == null)    /* there is no previous command */
            {
                // EMSG(_(e_nopresub)); TODO
                return false;
            }
            pat = null;             /* search_regcomp() will use previous pattern */
            sub = new CharPointer(lastReplace);
        }

        /*
        * Find trailing options.  When '&' is used, keep old options.
        */
        if (cmd.charAt() == '&')
        {
            cmd.inc();
        }
        else
        {
            do_all = Options.getInstance().isSet("gdefault");
            do_ask = false;
            do_error = true;
            do_print = false;
            do_ic = 0;
        }
        while (!cmd.isNul())
        {
            /*
            * Note that 'g' and 'c' are always inverted, also when p_ed is off.
            * 'r' is never inverted.
            */
            if (cmd.charAt() == 'g')
                do_all = !do_all;
            else if (cmd.charAt() == 'c')
                do_ask = !do_ask;
            else if (cmd.charAt() == 'e')
                do_error = !do_error;
            else if (cmd.charAt() == 'r')       /* use last used regexp */
                which_pat = RE_LAST;
            else if (cmd.charAt() == 'p')
                do_print = true;
            else if (cmd.charAt() == 'i')       /* ignore case */
                do_ic = 'i';
            else if (cmd.charAt() == 'I')       /* don't ignore case */
                do_ic = 'I';
            else
                break;
            cmd.inc();
        }

        int line1 = range.getStartLine();
        int line2 = range.getEndLine();

        /*
        * check for a trailing count
        */
        cmd = CharHelper.skipwhite(cmd);
        if (CharacterClasses.isDigit(cmd.charAt()))
        {
            int i = CharHelper.getdigits(cmd);
            if (i <= 0 && do_error)
            {
                // EMSG(_(e_zerocount)); TODO
                return false;
            }
            line1 = line2;
            line2 = EditorHelper.normalizeLine(editor, line1 + i - 1);
        }

        /*
        * check for trailing command or garbage
        */
        cmd = CharHelper.skipwhite(cmd);
        if (!cmd.isNul() && cmd.charAt() != '"')        /* if not end-of-line or comment */
        {
            // EMSG(_(e_trailing)); TODO
            return false;
        }

        String pattern = "";
        if (pat == null || pat.isNul())
        {
            switch (which_pat)
            {
                case RE_LAST:
                    pattern = lastPattern;
                    break;
                case RE_SEARCH:
                    pattern = lastSearch;
                    break;
                case RE_SUBST:
                    pattern = lastSubstitute;
                    break;
            }
        }
        else
        {
            pattern = pat.toString();
        }

        lastSubstitute = pattern;
        lastPattern = pattern;

        int start = editor.logicalPositionToOffset(new LogicalPosition(line1, 0));
        int end = editor.logicalPositionToOffset(new LogicalPosition(line2, EditorHelper.getLineLength(editor, line2)));

        RegExp sp;
        RegExp.regmmatch_T regmatch = new RegExp.regmmatch_T();
        sp = new RegExp();
        regmatch.regprog = sp.vim_regcomp(pattern, 1);
        if (regmatch.regprog == null)
        {
            if (do_error)
            {
                // EMSG(_(e_invcmd)); TODO
            }
            return false;
        }

        /* the 'i' or 'I' flag overrules 'ignorecase' and 'smartcase' */
        if (do_ic == 'i')
        {
            regmatch.rmm_ic = true;
        }
        else if (do_ic == 'I')
        {
            regmatch.rmm_ic = false;
        }

        /*
        * ~ in the substitute pattern is replaced with the old pattern.
        * We do it here once to avoid it to be replaced over and over again.
        * But don't do it when it starts with "\=", then it's an expression.
        */
        if (!(sub.charAt(0) == '\\' && sub.charAt(1) == '=') && lastReplace != null)
        {
            StringBuffer tmp = new StringBuffer(sub.toString());
            int pos = 0;
            while ((pos = tmp.indexOf("~", pos)) != -1)
            {
                if (pos == 0 || tmp.charAt(pos - 1) != '\\')
                {
                    tmp.replace(pos, pos + 1, lastReplace);
                    pos += lastReplace.length();
                }
                pos++;
            }
            sub = new CharPointer(tmp);
        }

        lastReplace = sub.toString();

        logger.debug("search range=[" + start + "," + end + "]");
        logger.debug("pattern="+pattern + ", replace="+sub);
        int lastMatch = -1;
        boolean found = true;
        int lastLine = -1;
        int searchcol = 0;
        boolean firstMatch = true;
        boolean got_quit = false;
        for (int lnum = line1; lnum <= line2 && !got_quit;)
        {
            LogicalPosition newpos = null;
            int nmatch = sp.vim_regexec_multi(regmatch, editor, lnum, searchcol);
            found = nmatch > 0;
            if (found)
            {
                if (firstMatch)
                {
                    CommandGroups.getInstance().getMark().saveJumpLocation(editor, context);
                    firstMatch = false;
                }

                String match = sp.vim_regsub_multi(regmatch, lnum, sub, 1, true);
                //logger.debug("found match[" + spos + "," + epos + "] - replace " + match);

                int line = lnum + regmatch.startpos[0].lnum;
                LogicalPosition startpos = new LogicalPosition(lnum + regmatch.startpos[0].lnum,
                    regmatch.startpos[0].col);
                LogicalPosition endpos = new LogicalPosition(lnum + regmatch.endpos[0].lnum,
                    regmatch.endpos[0].col);
                int startoff = editor.logicalPositionToOffset(startpos);
                int endoff = editor.logicalPositionToOffset(endpos);
                int newend = startoff + match.length();

                if (do_all || line != lastLine)
                {
                    boolean doReplace = true;
                    if (do_ask)
                    {
                        //editor.getSelectionModel().setSelection(startoff, endoff);
                        RangeHighlighter hl = highlightMatch(editor, startoff, endoff);
                        int choice = JOptionPane.showOptionDialog(null, "Replace with " + match + " ?",
                            "Confirm Replace", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                            getConfirmButtons(), null);
                        //editor.getSelectionModel().removeSelection();
                        editor.getMarkupModel().removeHighlighter(hl);
                        switch (choice)
                        {
                            case 0: // Yes
                                doReplace = true;
                                break;
                            case 1: // No
                                doReplace = false;
                                break;
                            case 2: // All
                                do_ask = false;
                                break;
                            case JOptionPane.CLOSED_OPTION:
                            case 3: // Quit
                                found = false;
                                doReplace = false;
                                got_quit = true;
                                break;
                            case 4: // Last
                                do_all = false;
                                line2 = lnum;
                                found = false;
                                doReplace = true;
                                break;
                        }
                    }

                    if (doReplace)
                    {
                        editor.getDocument().replaceString(startoff, endoff, match);
                        lastMatch = startoff;
                        newpos = editor.offsetToLogicalPosition(newend);

                        int diff = newpos.line - endpos.line;
                        line2 += diff;
                    }
                }

                lastLine = line;

                lnum += nmatch - 1;
                if (do_all)
                {
                    if (newpos != null)
                        searchcol = newpos.column;
                    else
                        searchcol = endpos.column;
                }
                else
                {
                    searchcol = 0;
                    lnum++;
                }
            }
            else
            {
                lnum++;
                searchcol = 0;
            }
        }

        if (lastMatch != -1)
        {
            MotionGroup.moveCaret(editor, context,
                CommandGroups.getInstance().getMotion().moveCaretToLineStartSkipLeading(editor,
                editor.offsetToLogicalPosition(lastMatch).line));
        }

        return res;
    }

    private boolean shouldIgnoreCase(String pattern, boolean noSmartCase)
    {
        boolean sc = noSmartCase ? false : Options.getInstance().isSet("smartcase");
        boolean ic = Options.getInstance().isSet("ignorecase");
        if (ic && !(sc && StringHelper.containsUpperCase(pattern)))
        {
            return true;
        }
        else
        {
            return false;
        }
    }

    public static int argsToFlags(String args)
    {
        int res = 0;
        boolean global = Options.getInstance().isSet("gdefault");
        for (int i = 0; i < args.length(); i++)
        {
            switch (args.charAt(i))
            {
                case '&':
                    res |= KEEP_FLAGS;
                    break;
                case 'c':
                    res |= CONFIRM;
                    break;
                case 'e':
                    res |= IGNORE_ERROR;
                    break;
                case 'g':
                    global = !global;
                    break;
                case 'i':
                    res |= IGNORE_CASE;
                    break;
                case 'I':
                    res |= NO_IGNORE_CASE;
                    break;
                case 'p':
                    res |= PRINT;
                    break;
                case 'r':
                    res |= REUSE;
                    break;
            }
        }

        if (global)
        {
            res |= GLOBAL;
        }

        return res;
    }

    private Object[] getConfirmButtons()
    {
        if (confirmBtns == null)
        {
            // TODO - need buttons with mnemonics
            /*
            confirmBtns = new JButton[] {
                new JButton("Yes"),
                new JButton("No"),
                new JButton("All"),
                new JButton("Quit"),
                new JButton("Last")
            };

            confirmBtns[0].setMnemonic('Y');
            confirmBtns[1].setMnemonic('N');
            confirmBtns[2].setMnemonic('A');
            confirmBtns[3].setMnemonic('Q');
            confirmBtns[4].setMnemonic('L');
            */
            confirmBtns = new String[] { "Yes", "No", "All", "Quit", "Last" };
        }

        return confirmBtns;
    }

    public int search(Editor editor, DataContext context, String command, int count, int flags, boolean moveCursor)
    {
        int res = search(editor, context, command, editor.getCaretModel().getOffset(), count, flags);

        if (res != -1 && moveCursor)
        {
            CommandGroups.getInstance().getMark().saveJumpLocation(editor, context);
            MotionGroup.moveCaret(editor, context, res);
        }

        return res;
    }

    public int search(Editor editor, DataContext context, String command, int startOffset, int count, int flags)
    {
        int dir = 1;
        char type = '/';
        String pattern = lastSearch;
        String offset = lastOffset;
        if ((flags & Command.FLAG_SEARCH_REV) != 0)
        {
            dir = -1;
            type = '?';
        }

        if (command.length() > 0)
        {
            if (command.charAt(0) != type)
            {
                CharPointer p = new CharPointer(command);
                CharPointer end = RegExp.skip_regexp(p.ref(0), type, true);
                pattern = p.substring(end.pointer() - p.pointer());
                logger.debug("pattern=" + pattern);
                if (p.charAt() != type)
                {
                    logger.debug("no offset");
                    offset = "";
                }
                else
                {
                    p.inc();
                    offset = p.toString();
                    logger.debug("offset=" + offset);
                }
            }
            else if (command.length() == 1)
            {
                offset = "";
            }
            else
            {
                offset = command.substring(1);
                logger.debug("offset=" + offset);
            }
        }

        lastSearch = pattern;
        lastPattern = pattern;
        lastOffset = offset;
        lastDir = dir;

        logger.debug("lastSearch=" + lastSearch);
        logger.debug("lastOffset=" + lastOffset);
        logger.debug("lastDir=" + lastDir);

        int res = findItOffset(editor, context, startOffset, count, lastDir, false);

        return res;
    }

    public int searchWord(Editor editor, DataContext context, int count, boolean whole, int dir)
    {
        TextRange range = SearchHelper.findWordUnderCursor(editor);
        if (range == null)
        {
            return -1;
        }

        StringBuffer pattern = new StringBuffer();
        if (whole)
        {
            pattern.append("\\<");
        }
        pattern.append(EditorHelper.getText(editor, range.getStartOffset(), range.getEndOffset()));
        if (whole)
        {
            pattern.append("\\>");
        }

        MotionGroup.moveCaret(editor, context, range.getStartOffset());

        lastSearch = pattern.toString();
        lastOffset = "";
        lastDir = dir;

        int res = findItOffset(editor, context, editor.getCaretModel().getOffset(), count, lastDir, true);

        return res;
    }

    public int searchNext(Editor editor, DataContext context, int count)
    {
        return findItOffset(editor, context, editor.getCaretModel().getOffset(), count, lastDir, false);
    }

    public int searchPrevious(Editor editor, DataContext context, int count)
    {
        return findItOffset(editor, context, editor.getCaretModel().getOffset(), count, -lastDir, false);
    }

    private int findItOffset(Editor editor, DataContext context, int startOffset, int count, int dir,
        boolean noSmartCase)
    {
        TextRange range = findIt(editor, context, startOffset, count, dir, noSmartCase);
        if (range == null)
        {
            return -1;
        }

        //highlightMatch(editor, range.getStartOffset(), range.getEndOffset());

        ParsePosition pp = new ParsePosition(0);
        int res = range.getStartOffset();

        if (lastOffset.length() == 0)
        {
            return range.getStartOffset();
        }
        else if (Character.isDigit(lastOffset.charAt(0)) || lastOffset.charAt(0) == '+' || lastOffset.charAt(0) == '-')
        {
            int lineOffset = 0;
            if (lastOffset.equals("+"))
            {
                lineOffset = 1;
            }
            else if (lastOffset.equals("-"))
            {
                lineOffset = -1;
            }
            else
            {
                if (lastOffset.charAt(0) == '+')
                {
                    lastOffset = lastOffset.substring(1);
                }
                NumberFormat nf = NumberFormat.getIntegerInstance();
                pp = new ParsePosition(0);
                Number num = nf.parse(lastOffset, pp);
                if (num != null)
                {
                    lineOffset = num.intValue();
                }
            }

            int line = editor.offsetToLogicalPosition(range.getStartOffset()).line;
            int newLine = EditorHelper.normalizeLine(editor, line + lineOffset);

            res = CommandGroups.getInstance().getMotion().moveCaretToLineStart(editor, newLine);
        }
        else if ("ebs".indexOf(lastOffset.charAt(0)) != -1)
        {
            int charOffset = 0;
            if (lastOffset.length() >= 2)
            {
                if ("+-".indexOf(lastOffset.charAt(1)) != -1)
                {
                    charOffset = 1;
                }
                NumberFormat nf = NumberFormat.getIntegerInstance();
                pp = new ParsePosition(lastOffset.charAt(1) == '+' ? 2 : 1);
                Number num = nf.parse(lastOffset, pp);
                if (num != null)
                {
                    charOffset = num.intValue();
                }
            }

            int base = range.getStartOffset();
            if (lastOffset.charAt(0) == 'e')
            {
                base = range.getEndOffset() - 1;
            }

            res = Math.max(0, Math.min(base + charOffset, EditorHelper.getFileSize(editor) - 1));
        }

        int ppos = pp.getIndex();
        if (ppos < lastOffset.length() - 1 && lastOffset.charAt(ppos) == ';')
        {
            int flags;
            if (lastOffset.charAt(ppos + 1) == '/')
            {
                flags = Command.FLAG_SEARCH_FWD;
            }
            else if (lastOffset.charAt(ppos + 1) == '?')
            {
                flags = Command.FLAG_SEARCH_REV;
            }
            else
            {
                return res;
            }

            if (lastOffset.length() - ppos > 2)
            {
                ppos++;
            }
            
            res = search(editor, context, lastOffset.substring(ppos + 1), res, 1, flags);

            return res;
        }
        else
        {
            return res;
        }
    }

    private TextRange findIt(Editor editor, DataContext context, int startOffset, int count, int dir,
        boolean noSmartCase)
    {
        TextRange res = null;

        if (lastSearch == null || lastSearch.length() == 0)
        {
            return res;
        }

        /*
        int pflags = RE.REG_MULTILINE;
        if (shouldIgnoreCase(lastSearch, noSmartCase))
        {
            pflags |= RE.REG_ICASE;
        }
        */
        //RE sp;
        RegExp sp;
        RegExp.regmmatch_T regmatch = new RegExp.regmmatch_T();
        regmatch.rmm_ic = shouldIgnoreCase(lastSearch, noSmartCase);
        sp = new RegExp();
        regmatch.regprog = sp.vim_regcomp(lastSearch, 1);
        if (regmatch == null)
        {
            logger.debug("bad pattern: " + lastSearch);
            return res;
        }

        /*
        int extra_col = 1;
        int startcol = -1;
        boolean found = false;
        boolean match_ok = true;
        LogicalPosition pos = editor.offsetToLogicalPosition(startOffset);
        LogicalPosition endpos = null;
        //REMatch match = null;
        */

        LogicalPosition lpos = editor.offsetToLogicalPosition(startOffset);
        RegExp.lpos_T pos = new RegExp.lpos_T();
        pos.lnum = lpos.line;
        pos.col = lpos.column;

        int    found;
        int    lnum;           /* no init to shut up Apollo cc */
        //RegExp.regmmatch_T regmatch;
        CharPointer ptr = null;
        int     matchcol;
        int     startcol;
        RegExp.lpos_T      endpos = new RegExp.lpos_T();
        int         loop;
        RegExp.lpos_T       start_pos;
        boolean         at_first_line;
        int         extra_col = 1;
        boolean         match_ok;
        long        nmatched;
        //int         submatch = 0;
        int    first_lnum;
        boolean p_ws = Options.getInstance().isSet("wrapscan");

        do  /* loop for count */
        {
            start_pos = new RegExp.lpos_T(pos);       /* remember start pos for detecting no match */
            found = 0;              /* default: not found */
            at_first_line = true;   /* default: start in first line */
            if (pos.lnum == -1)     /* correct lnum for when starting in line 0 */
            {
                pos.lnum = 0;
                pos.col = 0;
                at_first_line = false;  /* not in first line now */
            }

            /*
            * Start searching in current line, unless searching backwards and
            * we're in column 0.
            */
            if (dir == -1 && start_pos.col == 0)
            {
                lnum = pos.lnum - 1;
                at_first_line = false;
            }
            else
            {
                lnum = pos.lnum;
            }

            for (loop = 0; loop <= 1; ++loop)   /* loop twice if 'wrapscan' set */
            {
                int lineCount = EditorHelper.getLineCount(editor);
                for ( ; lnum >= 0 && lnum < lineCount; lnum += dir, at_first_line = false)
                {
                    /*
                    * Look for a match somewhere in the line.
                    */
                    first_lnum = lnum;
                    nmatched = sp.vim_regexec_multi(regmatch, editor, lnum, 0);
                    if (nmatched > 0)
                    {
                        /* match may actually be in another line when using \zs */
                        lnum += regmatch.startpos[0].lnum;
                        ptr = new CharPointer(EditorHelper.getLineBuffer(editor, lnum));
                        startcol = regmatch.startpos[0].col;
                        endpos = regmatch.endpos[0];

                        /*
                        * Forward search in the first line: match should be after
                        * the start position. If not, continue at the end of the
                        * match (this is vi compatible) or on the next char.
                        */
                        if (dir == 1 && at_first_line)
                        {
                            match_ok = true;
                            /*
                            * When match lands on a NUL the cursor will be put
                            * one back afterwards, compare with that position,
                            * otherwise "/$" will get stuck on end of line.
                            */
                            while ((startcol - (startcol == ptr.strlen() ? 1 : 0)) < (start_pos.col + extra_col))
                            {
                                if (nmatched > 1)
                                {
                                    /* end is in next line, thus no match in
                                    * this line */
                                    match_ok = false;
                                    break;
                                }
                                matchcol = endpos.col;
                                /* for empty match: advance one char */
                                if (matchcol == startcol && ptr.charAt(matchcol) != '\u0000')
                                {
                                    ++matchcol;
                                }
                                if (ptr.charAt(matchcol) == '\u0000' ||
                                    (nmatched = sp.vim_regexec_multi(regmatch, editor, lnum, matchcol)) == 0)
                                {
                                    match_ok = false;
                                    break;
                                }
                                startcol = regmatch.startpos[0].col;
                                endpos = regmatch.endpos[0];

                                /* Need to get the line pointer again, a
                                 * multi-line search may have made it invalid. */
                                ptr = new CharPointer(EditorHelper.getLineBuffer(editor, lnum));
                            }
                            if (!match_ok)
                            {
                                continue;
                            }
                        }
                        if (dir == -1)
                        {
                            /*
                            * Now, if there are multiple matches on this line,
                            * we have to get the last one. Or the last one before
                            * the cursor, if we're on that line.
                            * When putting the new cursor at the end, compare
                            * relative to the end of the match.
                            */
                            match_ok = false;
                            for (;;)
                            {
                                if (!at_first_line || (regmatch.startpos[0].col + extra_col <= start_pos.col))
                                {
                                    /* Remember this position, we use it if it's
                                    * the last match in the line. */
                                    match_ok = true;
                                    startcol = regmatch.startpos[0].col;
                                    endpos = regmatch.endpos[0];
                                }
                                else
                                {
                                    break;
                                }

                                /*
                                * We found a valid match, now check if there is
                                * another one after it.
                                * If vi-compatible searching, continue at the end
                                * of the match, otherwise continue one position
                                * forward.
                                */
                                if (nmatched > 1)
                                {
                                    break;
                                }
                                matchcol = endpos.col;
                                /* for empty match: advance one char */
                                if (matchcol == startcol && ptr.charAt(matchcol) != '\u0000')
                                {
                                    ++matchcol;
                                }
                                if (ptr.charAt(matchcol) == '\u0000' ||
                                    (nmatched = sp.vim_regexec_multi(regmatch, editor, lnum, matchcol)) == 0)
                                {
                                    break;
                                }

                                /* Need to get the line pointer again, a
                                * multi-line search may have made it invalid. */
                                ptr = new CharPointer(EditorHelper.getLineBuffer(editor, lnum));
                            }

                            /*
                            * If there is only a match after the cursor, skip
                            * this match.
                            */
                            if (!match_ok)
                            {
                                continue;
                            }
                        }

                        pos.lnum = lnum;
                        pos.col = startcol;
                        endpos.lnum += first_lnum;
                        found = 1;

                        /* Set variables used for 'incsearch' highlighting. */
                        //search_match_lines = endpos.lnum - (lnum - first_lnum);
                        //search_match_endcol = endpos.col;
                        break;
                    }
                    //line_breakcheck();      /* stop if ctrl-C typed */
                    //if (got_int)
                    //    break;


                    if (loop != 0 && lnum == start_pos.lnum)
                    {
                        break;          /* if second loop, stop where started */
                    }
                }
                at_first_line = false;

                /*
                * stop the search if wrapscan isn't set, after an interrupt and
                * after a match
                */
                if (!p_ws || found != 0)
                {
                    break;
                }

                /*
                * If 'wrapscan' is set we continue at the other end of the file.
                * If 'shortmess' does not contain 's', we give a message.
                * This message is also remembered in keep_msg for when the screen
                * is redrawn. The keep_msg is cleared whenever another message is
                * written.
                */
                if (dir == -1)    /* start second loop at the other end */
                {
                    lnum = lineCount - 1;
                    //if (!shortmess(SHM_SEARCH) && (options & SEARCH_MSG))
                    //    give_warning((char_u *)_(top_bot_msg), TRUE);
                }
                else
                {
                    lnum = 0;
                    //if (!shortmess(SHM_SEARCH) && (options & SEARCH_MSG))
                    //    give_warning((char_u *)_(bot_top_msg), TRUE);
                }
            }
            //if (got_int || called_emsg || break_loop)
            //    break;
        }
        while (--count > 0 && found != 0);   /* stop after count matches or no match */

        if (found == 0)             /* did not find it */
        {
            /*
            if (got_int)
                EMSG(_(e_interr));
            else if ((options & SEARCH_MSG) == SEARCH_MSG)
            {
                if (p_ws)
                    EMSG2(_(e_patnotf2), mr_pattern);
                else if (lnum == 0)
                    EMSG2(_("E384: search hit TOP without match for: %s"), mr_pattern);
                else
                    EMSG2(_("E385: search hit BOTTOM without match for: %s"), mr_pattern);
            }
            */
            return null;
        }

        return new TextRange(editor.logicalPositionToOffset(new LogicalPosition(pos.lnum, pos.col)),
            editor.logicalPositionToOffset(new LogicalPosition(endpos.lnum, endpos.col)));
    }

    private RangeHighlighter highlightMatch(Editor editor, int start, int end)
    {
        return editor.getMarkupModel().addRangeHighlighter(start, end, HighlighterLayer.SELECTION,
            new TextAttributes(Color.BLACK, Color.YELLOW, null, null, 0), HighlighterTargetArea.EXACT_RANGE);
    }

    private String lastSearch;
    private String lastPattern;
    private String lastSubstitute;
    private String lastReplace;
    private String lastOffset;
    private int lastDir;
    private Object[] confirmBtns;

    private boolean do_all = false; /* do multiple substitutions per line */
    private boolean do_ask = false; /* ask for confirmation */
    private boolean do_error = true; /* if false, ignore errors */
    private boolean do_print = false; /* print last line with subs. */
    private char do_ic = 0; /* ignore case flag */

    private static final int RE_LAST = 1;
    private static final int RE_SEARCH = 2;
    private static final int RE_SUBST = 3;

    private static Logger logger = Logger.getInstance(SearchGroup.class.getName());
}