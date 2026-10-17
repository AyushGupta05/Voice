import { AwsSdkSigV4Signer } from '@aws-sdk/core/httpAuthSchemes';
import type { BedrockClientConfig } from '@aws-sdk/client-bedrock';

/** Never let environment/shared bearer tokens change the account verified through STS. */
export function bedrockSigV4Only(): Pick<BedrockClientConfig, 'authSchemePreference' | 'httpAuthSchemes' | 'httpAuthSchemeProvider'> {
  return {
    authSchemePreference: ['sigv4'],
    httpAuthSchemes: [{
      schemeId: 'aws.auth#sigv4',
      identityProvider: configuration => configuration.getIdentityProvider('aws.auth#sigv4'),
      signer: new AwsSdkSigV4Signer(),
    }],
    httpAuthSchemeProvider: parameters => [{
      schemeId: 'aws.auth#sigv4',
      signingProperties: { name: 'bedrock', region: parameters.region },
      propertiesExtractor: (config, context) => ({ signingProperties: { config, context } }),
    }],
  };
}
