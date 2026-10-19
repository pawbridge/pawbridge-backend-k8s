#!/usr/bin/env python3
"""Pin recipient lookup inputs and six verbatim methods, without a legacy application."""
import hashlib
import pathlib
import subprocess
import sys

SOURCE = '33cad5567767f8730fc2c4389815ecd031f41dc0'
USER = 'user-service/src/main/java/'
COMMUNITY = 'community-service/src/main/java/'
EXPECTED = {
    USER + 'com/pawbridge/userservice/contact/ContactMember.java': '467aed47cf77982a7f30ae5efa318b78981449f80896f003eb82cae80ae8a732',
    USER + 'com/pawbridge/userservice/contact/ContactMemberQuery.java': '287e88daea9abae4624a94aa89af794454aec47b3322156c5ba74315e782198e',
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteRepository.java': '06cc66d2e6b6d269bd51f30338d80fe33cdba4ef1085f2e2f1f0413ffdbfe772',
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteService.java': 'f0118b39e5b4a99b7592ecfd3257f4f11142306355d0503a52ec48c0da251acb',
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteController.java': '3c27ab84fa9326c9de8e0bd112b7f47778150fce7c5cdae0f3073bd316898303',
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java': '99c39401f41509a6fb05b5f68ae967fe5e08a32d6dcee399cb39b1f7dc9bce3b',
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java': 'b6b5f3b516bc5118a3ee0ea73945df9ee9846e78bcf7d6dfee81d711e30a7ed3',
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteModels.java': '5f4c2e1026025aedaaed24922faf440805323757c3b2d2589cf69f80da0f849a',
    COMMUNITY + 'com/pawbridge/communityservice/client/UserServiceClient.java': '7dfe06774f5e44c18597cbc9f1b3b0670880807f19e70df5ce76c26ff41be216',
}
METHODS = {
    '    public ContactMember recipient(long currentUserId, long recipientId) {': '133199bcbd4539903acad0dfba4afe1568d4b270d9ed5c5c3e122fc8570932f8',
    '    private ContactMember active(long userId) {': '20717cc58bc2264b90a12157a8d868ef69e04a8baa949936489c82f26003d745',
    '    private ContactMember lookup(long userId) {': '9667ffbd6c876303de0f5b02929aed4ab5dc3d4ce50c737e464e62d292379bbf',
    '    private static ResponseStatusException memberLookupUnavailable() {': 'd05eecaca914fd6b267439710d57fc313e6c77d303fd64f1b3589b64de12aecc',
    '    private static void requireId(long userId) {': '18d4ee2172744a8cfb5711f2d4b21fbc9c8e5052ad14f6be91f0b2866603f031',
    '    private static ResponseStatusException error(HttpStatus status, String message) {': '6a0d213030b2d6481724b2a74ebf6b3c4ea1d40a80be03e927efe77b416d4dad',
}
COPIED = tuple(source for source in EXPECTED if source.startswith(USER)) + (
    COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteRepository.java',)
SHARED = (COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteModels.java',
          COMMUNITY + 'com/pawbridge/communityservice/client/UserServiceClient.java')


def verified_method(text, signature, expected):
    if text.count(signature) != 1:
        raise SystemExit('F17 method signature is not unique')
    begin = text.index(signature)
    cursor, balance = text.index('{', begin), 0
    for cursor in range(cursor, len(text)):
        if text[cursor] == '{':
            balance += 1
        elif text[cursor] == '}':
            balance -= 1
            if balance == 0:
                if text[cursor + 1:cursor + 2] != '\n':
                    raise SystemExit('F17 method requires its pinned trailing LF')
                method = text[begin:cursor + 2]
                if hashlib.sha256(method.encode()).hexdigest() != expected:
                    raise SystemExit('F17 verbatim method checksum mismatch')
                return method
    raise SystemExit('F17 method body is incomplete')


def extract(repository, output, evidence):
    repository, output, evidence = [pathlib.Path(v).resolve() for v in (repository, output, evidence)]
    if (output != repository / 'backend-app/boot/build/generated/sources/f17LegacyOracle/java'
            or evidence != repository / 'backend-app/build/ci/f17-oracle-sha256.txt'):
        raise SystemExit('Refusing non-dedicated F17 oracle output')
    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if hashlib.sha256(content).hexdigest() != expected:
            raise SystemExit('Legacy F17 input checksum mismatch: ' + source)
        inputs[source] = content
        entries.append(expected + '  ' + source)
    for source in SHARED:
        generated = repository / 'backend-app/boot/build/generated/sources/f2LegacyOracle/java' / source[len(COMMUNITY):]
        if not generated.is_file() or generated.read_bytes() != inputs[source]:
            raise SystemExit('Shared F2 source differs from fixed F17 input: ' + source)
    text = inputs[COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteService.java'].decode()
    methods = [verified_method(text, signature, expected) for signature, expected in METHODS.items()]
    entries.extend(expected + '  verified-method ' + signature.strip() for signature, expected in METHODS.items())
    wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Test-only wiring; only the six hash-pinned method bodies are original. */
public final class F17LegacyRecipientService {
    private final PrivateNoteRepository privateNoteRepository;
    private final UserServiceClient userServiceClient;
    public F17LegacyRecipientService(PrivateNoteRepository repository, UserServiceClient client) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
    }
''' + '\n\n'.join(methods) + '\n}\n'
    pending = []
    for source in COPIED:
        prefix = USER if source.startswith(USER) else COMMUNITY
        pending.append((output / source[len(prefix):], inputs[source]))
    pending.append((output / 'com/pawbridge/communityservice/contact/F17LegacyRecipientService.java', wrapper.encode()))
    for target, content in pending:
        if target.exists() and target.read_bytes() != content:
            raise SystemExit('Refusing to overwrite differing F17 oracle input')
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n')
    print('Verified nine inputs, F2 shared bytes and six original recipient methods; no legacy application started')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F17 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
