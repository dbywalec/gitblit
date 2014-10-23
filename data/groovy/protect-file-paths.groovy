/*
 * Copyright 2014 Trapeze Poland.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.gitblit.GitBlit
import com.gitblit.Keys
import com.gitblit.models.RepositoryModel
import com.gitblit.models.TeamModel
import com.gitblit.models.UserModel
import com.gitblit.utils.JGitUtils
import com.sun.org.apache.xalan.internal.xsltc.compiler.Import;

import java.text.SimpleDateFormat
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceiveCommand.Result
import org.slf4j.Logger

import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Gitblit Pre-Receive Hook: protect-file-paths
 *
 * This script provides basic authorization of receive commits with respect to list
 * of known protected file path patterns. Unmatched file path patterns will be
 * ignored, meaning this script has an "allow by default" policy.
 *
 * The Pre-Receive hook is executed after an incoming push has been parsed,
 * validated, and objects have been written but BEFORE the refs are updated.
 * This is the appropriate point to block a push for some reason.
 *
 * This script is only executed when pushing to *Gitblit*, not to other Git
 * tooling you may be using.
 * 
 * If this script is specified in *groovy.preReceiveScripts* of gitblit.properties
 * or web.xml then it will be executed by any repository when it receives a
 * push.  If you choose to share your script then you may have to consider
 * tailoring control-flow based on repository access restrictions.
 * 
 * Scripts may also be specified per-repository in the repository settings page.
 * Shared scripts will be excluded from this list of available scripts.
 *
 * This script is dynamically reloaded and it is executed within it's own
 * exception handler so it will not crash another script nor crash Gitblit.
 * 
 * If you want this hook script to fail and abort all subsequent scripts in the
 * chain, "return false" at the appropriate failure points.
 * 
 * Bound Variables:
 *  gitblit			Gitblit Server	 			com.gitblit.GitBlit
 *  repository		Gitblit Repository			com.gitblit.models.RepositoryModel
 *  receivePack		JGit Receive Pack			org.eclipse.jgit.transport.ReceivePack
 *  user			Gitblit User				com.gitblit.models.UserModel
 *  commands		JGit commands 				Collection<org.eclipse.jgit.transport.ReceiveCommand>
 *	url				Base url for Gitblit		String
 *  logger			Logs messages to Gitblit 	org.slf4j.Logger
 *  clientLogger	Logs messages to Git client	com.gitblit.utils.ClientLogger
 *
 * Accessing Gitblit Custom Fields:
 *   def myCustomField = repository.customFields.myCustomField
 * 
 * Custom Fields Used by This script
 *   protectedFilePathsPattern - the definition of protected file path pattern (regex)
 *   protectedFilePathsAuthorizedTeams - comma separated list of team names authorised to modify protected file paths
 */

// Indicate we have started the script
logger.info("protect-file-paths hook triggered by ${user.username} for ${repository.name}")

// pull custom fields from repository specific values
// groovy.customFields = "protectedFilePathsPattern=Protected File Paths Pattern" "protectedFilePathsAuthorizedTeamsPattern=Protected File Paths Authorized Teams Pattern"
def protectedFilePathsPattern = repository.customFields.protectedFilePathsPattern
// teams which are authorized to perform protected commands on protected file paths
def protectedFilePathsAuthorizedTeams = repository.customFields.protectedFilePathsAuthorizedTeams.split(',')

def result = true;

if ( protectedFilePathsPattern && protectedFilePathsAuthorizedTeams ) {

	// Obtain the instance of the repository of given name
	Repository r = gitblit.getRepository(repository.name)

	for (command in commands) {

		def updateType = command.type
		def updatedRef = command.refName

		for( commit in JGitUtils.getRevLog(r, command.oldId.name, command.newId.name).reverse() ) {
			
			DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)
			formatter.setRepository(r)
			formatter.setDetectRenames(true)
			formatter.setDiffComparator(RawTextComparator.DEFAULT);

			def diffs
			RevWalk rw = new RevWalk(r)
			if (commit.parentCount > 0) {
				RevCommit parent = rw.parseCommit(commit.parents[0].id)
				diffs = formatter.scan(parent.tree, commit.tree)
			} else {
				diffs = formatter.scan(new EmptyTreeIterator(),
									   new CanonicalTreeParser(null, rw.objectReader, commit.tree))
			}
			rw.dispose()
			
			// Traverse each filepath
			for (DiffEntry entry in diffs) {
				FileHeader header = formatter.toFileHeader(entry)
				
				def pathPattern = header.newPath =~ protectedFilePathsPattern
				
				// command requires authorisation if path is protected and has a mapped rejection result
				if (pathPattern) {
				
					// verify user is a member of any authorized team
					def team = protectedFilePathsAuthorizedTeams.find { user.isTeamMember it }
					if (team) {
						// don't adjust command result
						logger.info "${user.username} authorized for ${updateType} of protected file path ${header.newPath} on ${repository.name}:${updatedRef}"
					} else {
						// mark command result as rejected
						command.setResult(Result.REJECTED_OTHER_REASON, "${user.username} cannot ${updateType} protected file path ${header.newPath} matching pattern ${pathPattern} on ${repository.name}:${updatedRef}")
					}
				}
			}
		}
	}

	// close the repository reference
	r.close()
}

if (!result) {
  return false
}
